package com.buboadonis.s7health.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.buboadonis.s7health.R
import com.buboadonis.s7health.databinding.ActivityMainBinding
import com.buboadonis.s7health.model.BadRegion
import com.buboadonis.s7health.model.HealthStatus
import com.buboadonis.s7health.model.ScanMode
import com.buboadonis.s7health.model.ScanProgress
import com.buboadonis.s7health.model.ScanState
import com.buboadonis.s7health.scanner.RawBlockScanner
import com.buboadonis.s7health.service.ScanService
import com.buboadonis.s7health.util.CheckpointStore
import com.buboadonis.s7health.util.RootRepairManager
import com.buboadonis.s7health.util.SectorMapStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var checkpointStore: CheckpointStore
    private lateinit var sectorMapStore: SectorMapStore
    private lateinit var repairManager: RootRepairManager
    private var lastProgress: ScanProgress = ScanProgress()
    private val logLines = ArrayDeque<String>()
    private val maxLogLines = 300
    private val handler = Handler(Looper.getMainLooper())
    private var polling = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> appendLog("Permissions updated. You can start a scan.") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkpointStore = CheckpointStore(this)
        sectorMapStore = SectorMapStore(this)
        repairManager = RootRepairManager(this)

        // Single smart button - auto-determines best action
        binding.btnSmartScan.setOnClickListener { onSmartScanClicked() }
        binding.btnStop.setOnClickListener { stopScan() }
        binding.btnViewResults.setOnClickListener { viewResults() }

        // Advanced toggle
        binding.advancedToggle.setOnClickListener {
            val visible = binding.advancedSection.visibility == android.view.View.VISIBLE
            binding.advancedSection.visibility = if (visible) android.view.View.GONE else android.view.View.VISIBLE
            binding.advancedToggle.text = if (visible) "Advanced tools ▼" else "Advanced tools ▲"
        }

        // Advanced buttons (kept for manual overrides)
        binding.btnScanStorage.setOnClickListener { onSmartScanClicked() }
        binding.btnScanRaw.setOnClickListener { onSmartScanClicked() }
        binding.btnRepair.setOnClickListener { repairOrMark() }
        binding.btnExport.setOnClickListener { exportReport() }
        binding.btnExportBadBlocks.setOnClickListener { exportBadBlocksMap() }
        binding.btnApplyBadBlocks.setOnClickListener { applyBadBlocksFlow() }
        binding.btnViewBadSegments.setOnClickListener { viewBadSegments() }
        binding.btnExportRecoveryScript.setOnClickListener { exportRecoveryScript() }
        binding.switchPremium.setOnCheckedChangeListener { _, checked -> setPremiumMode(checked) }
        binding.btnApplyUnmounted.setOnClickListener { applyOnUnmountedFlow() }
        binding.btnExportMagiskScript.setOnClickListener { exportMagiskBootScript() }

        val premium = getSharedPreferences("s7health", MODE_PRIVATE).getBoolean("premium_mode", false)
        binding.switchPremium.isChecked = premium
        binding.premiumActions.visibility = if (premium) android.view.View.VISIBLE else android.view.View.GONE

        updateUi(lastProgress)
        ensurePermissions()
        appendLog("S7 Health Scanner ready. Target device: Samsung Galaxy S7 Edge (or any Android device).")
        appendLog("Tap Start - the app auto-detects root/raw vs file scan and runs in background (screen off OK).")
        appendLog("If phone reboots/crashes, scan auto-resumes from checkpoint. No need to tap Resume.")

        // Auto-resume if checkpoint exists (no dialog)
        val cp = checkpointStore.load()
        if (cp != null) {
            val ageMin = (System.currentTimeMillis() - cp.timestamp) / 60000L
            appendLog("Found saved checkpoint (${ageMin} min ago). Auto-resuming scan in background...")
            ScanService.start(this, ScanService.ACTION_RESUME)
        }

        // Check battery optimization hint
        checkBatteryOptimization()

        startPolling()
    }

    override fun onResume() {
        super.onResume()
        // Re-check checkpoint and auto-resume if needed
        val cp = checkpointStore.load()
        if (cp != null) {
            // Service may already be running; starting again is safe (service checks isActive)
            ScanService.start(this, ScanService.ACTION_RESUME)
        }
        pollProgress()
    }

    override fun onPause() {
        super.onPause()
        // Do NOT stop service - it continues in background
    }

    override fun onDestroy() {
        super.onDestroy()
        polling = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun startPolling() {
        polling = true
        handler.post(object : Runnable {
            override fun run() {
                if (!polling) return
                pollProgress()
                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun pollProgress() {
        // Read last_progress.json written by service
        try {
            val file = File(filesDir, "last_progress.json")
            if (file.exists()) {
                val json = JSONObject(file.readText())
                val badArr = json.optJSONArray("badRegions") ?: JSONArray()
                val bad = mutableListOf<BadRegion>()
                for (i in 0 until badArr.length()) {
                    val o = badArr.getJSONObject(i)
                    bad.add(
                        BadRegion(
                            pathOrDevice = o.optString("pathOrDevice"),
                            offsetBytes = o.optLong("offsetBytes"),
                            lengthBytes = o.optLong("lengthBytes"),
                            description = o.optString("description"),
                            repaired = o.optBoolean("repaired"),
                            markedUnusable = o.optBoolean("markedUnusable")
                        )
                    )
                }
                val progress = ScanProgress(
                    state = ScanState.valueOf(json.optString("state", "IDLE")),
                    mode = ScanMode.valueOf(json.optString("mode", "FILE_LEVEL")),
                    bytesScanned = json.optLong("bytesScanned"),
                    totalBytesEstimate = json.optLong("totalBytesEstimate"),
                    badRegions = bad,
                    currentTarget = json.optString("currentTarget"),
                    message = json.optString("message", "Ready."),
                    health = HealthStatus.valueOf(json.optString("health", "UNKNOWN")),
                    readErrors = json.optLong("readErrors"),
                    writeErrors = json.optLong("writeErrors"),
                    verifiedBytes = json.optLong("verifiedBytes"),
                    speedBytesPerSec = json.optLong("speedBytesPerSec")
                )
                // Only update if newer or different
                if (progress != lastProgress) {
                    updateUi(progress)
                }
            }
        } catch (_: Throwable) {}

        // Update smart button label based on checkpoint / running state
        val cp = try { checkpointStore.load() } catch (_: Throwable) { null }
        val isScanning = lastProgress.state == ScanState.SCANNING || lastProgress.state == ScanState.REPAIRING
        binding.btnSmartScan.text = when {
            isScanning -> getString(R.string.scanning)
            cp != null -> getString(R.string.resume_scan)
            else -> getString(R.string.smart_scan)
        }
        binding.btnSmartScan.isEnabled = !isScanning
        binding.btnStop.isEnabled = isScanning
    }

    private fun ensurePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT <= 29) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (Build.VERSION.SDK_INT <= 32) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO).forEach {
                if (ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED) needed.add(it)
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            appendLog("For broad storage scans on Android 11+, grant 'All files access' in settings.")
        }
    }

    private fun checkBatteryOptimization() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                val isIgnoring = pm.isIgnoringBatteryOptimizations(packageName)
                if (!isIgnoring) {
                    binding.batteryHint.visibility = android.view.View.VISIBLE
                    binding.batteryHint.setOnClickListener {
                        try {
                            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                        } catch (_: Throwable) {
                            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun onSmartScanClicked() {
        val cp = checkpointStore.load()
        if (cp != null) {
            // Resume automatically - no dialog
            appendLog("Resuming scan from checkpoint (${RawBlockScanner.formatBytes(cp.bytesScanned)} scanned)...")
            ScanService.start(this, ScanService.ACTION_RESUME)
            toast("Resuming scan in background. You can turn screen off.")
            return
        }

        // No checkpoint: check permission for file scan, then start service which auto-detects mode
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            AlertDialog.Builder(this)
                .setTitle("Storage permission")
                .setMessage("For a full accessible-storage scan, allow 'All files access'. Continue with limited scan?")
                .setPositiveButton("Open settings") { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                    } catch (_: Throwable) {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
                .setNegativeButton("Limited scan") { _, _ -> startServiceScan() }
                .show()
            return
        }
        startServiceScan()
    }

    private fun startServiceScan() {
        appendLog("Starting smart scan (auto-detecting root/raw vs file)...")
        ScanService.start(this, ScanService.ACTION_START)
        toast("Scanning in background. Screen off is OK.")
    }

    private fun stopScan() {
        ScanService.stop(this)
        appendLog("Stop requested. Progress saved for auto-resume.")
        toast("Scan stopped. Progress saved - will auto-resume on next start/boot.")
    }

    private fun viewResults() {
        val p = lastProgress
        val sb = StringBuilder()
        sb.appendLine("Health: ${p.health}")
        sb.appendLine("State: ${p.state} (${p.mode})")
        sb.appendLine("Scanned: ${RawBlockScanner.formatBytes(p.bytesScanned)}")
        sb.appendLine("Verified: ${RawBlockScanner.formatBytes(p.verifiedBytes)}")
        sb.appendLine("Read errors: ${p.readErrors}, Write errors: ${p.writeErrors}")
        sb.appendLine("Bad regions: ${p.badRegions.size}")
        sb.appendLine()
        if (p.badRegions.isEmpty()) {
            sb.appendLine("No issues found in last scan.")
        } else {
            p.badRegions.take(30).forEachIndexed { i, r ->
                sb.appendLine("${i + 1}. ${r.pathOrDevice} @${r.offsetBytes}+${r.lengthBytes}")
                sb.appendLine("   ${r.description}")
            }
            if (p.badRegions.size > 30) sb.appendLine("... ${p.badRegions.size - 30} more")
        }
        sb.appendLine()
        sb.appendLine("Sector map:")
        val summary = sectorMapStore.summary()
        if (summary.isEmpty()) {
            sb.appendLine("No sector map data yet.")
        } else {
            summary.forEach { (target, counts) ->
                sb.appendLine("Target: $target")
                counts.forEach { (status, bytes) -> sb.appendLine("  $status: ${RawBlockScanner.formatBytes(bytes)}") }
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Scan Results (auto-generated)")
            .setMessage(sb.toString().take(4000))
            .setPositiveButton("OK", null)
            .setNeutralButton("Export Report") { _, _ -> exportReport() }
            .show()
    }

    // --- Advanced / manual actions (kept for power users) ---

    private fun repairOrMark() {
        val progress = lastProgress
        val badFiles = progress.badRegions
            .filter { !it.markedUnusable && !it.repaired }
            .map { File(it.pathOrDevice) }
            .filter { it.exists() && it.isFile && !it.name.startsWith(".s7health_") }
            .distinctBy { it.absolutePath }

        if (badFiles.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Repair / Mark Unusable")
                .setMessage(
                    "No quarantinable bad files from the last scan.\n\n" +
                    "Notes:\n" +
                    "• File-level: unreadable files can be quarantined (moved to app-private storage) so other apps cannot access them.\n" +
                    "• Raw block: physical bad sectors cannot be remapped by an unprivileged app. With root, you can use fsck/badblocks on unmounted partitions. Export the report for details.\n" +
                    "• This app never writes to raw block devices."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Quarantine bad files")
            .setMessage("Move ${badFiles.size} damaged/unreadable file(s) to app-private quarantine so other apps cannot access them? Original paths will be recorded in the report.")
            .setPositiveButton("Quarantine") { _, _ -> quarantineFiles(badFiles) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun quarantineFiles(files: List<File>) {
        lifecycleScope.launch {
            val quarantineDir = File(filesDir, "quarantine").apply { mkdirs() }
            var moved = 0
            val updated = lastProgress.badRegions.toMutableList()
            withContext(Dispatchers.IO) {
                for (f in files) {
                    try {
                        val target = File(quarantineDir, f.name + ".quarantined-" + System.currentTimeMillis())
                        val ok = f.renameTo(target) || copyThenDelete(f, target)
                        if (ok) {
                            moved++
                            val idx = updated.indexOfFirst { it.pathOrDevice == f.absolutePath }
                            if (idx >= 0) {
                                updated[idx] = updated[idx].copy(markedUnusable = true, description = updated[idx].description + " [quarantined to ${target.absolutePath}]")
                            }
                            appendLogUi("Quarantined: ${f.absolutePath} -> ${target.absolutePath}")
                        } else {
                            appendLogUi("Failed to quarantine: ${f.absolutePath}")
                        }
                    } catch (t: Throwable) {
                        appendLogUi("Quarantine error for ${f.absolutePath}: ${t.message}")
                    }
                }
            }
            lastProgress = lastProgress.copy(badRegions = updated, message = "Quarantined $moved/${files.size} file(s).")
            updateUi(lastProgress)
            toast("Quarantined $moved file(s).")
        }
    }

    private fun copyThenDelete(src: File, dst: File): Boolean {
        return try {
            src.inputStream().use { inp -> dst.outputStream().use { out -> inp.copyTo(out) } }
            if (dst.exists() && dst.length() == src.length()) src.delete() else false
        } catch (_: Throwable) { false }
    }

    private fun exportReport() {
        lifecycleScope.launch {
            val report = buildReport(lastProgress)
            val dir = getExternalFilesDir(null) ?: filesDir
            val name = "s7-health-report-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
            val file = File(dir, name)
            val ok = withContext(Dispatchers.IO) {
                try { file.writeText(report); true } catch (_: Throwable) { false }
            }
            if (ok) {
                appendLog("Report exported: ${file.absolutePath}")
                toast("Report saved: ${file.absolutePath}")
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Report exported")
                    .setMessage(file.absolutePath)
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                toast("Failed to export report.")
            }
        }
    }

    private fun buildReport(p: ScanProgress): String {
        val sb = StringBuilder()
        sb.appendLine("S7 Health Scanner Report")
        sb.appendLine("Generated: ${Date()}")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("App version: ${packageManager.getPackageInfo(packageName, 0).versionName}")
        sb.appendLine()
        sb.appendLine("State: ${p.state}")
        sb.appendLine("Mode: ${p.mode}")
        sb.appendLine("Health: ${p.health}")
        sb.appendLine("Bytes scanned: ${p.bytesScanned}")
        sb.appendLine("Verified bytes: ${p.verifiedBytes}")
        sb.appendLine("Read errors: ${p.readErrors}")
        sb.appendLine("Write errors: ${p.writeErrors}")
        sb.appendLine("Bad regions: ${p.badRegions.size}")
        sb.appendLine("Message: ${p.message}")
        sb.appendLine()
        sb.appendLine("Bad regions / issues:")
        if (p.badRegions.isEmpty()) {
            sb.appendLine("  None")
        } else {
            p.badRegions.forEachIndexed { i, r ->
                sb.appendLine("  ${i + 1}. ${r.pathOrDevice}")
                sb.appendLine("     offset=${r.offsetBytes} length=${r.lengthBytes} repaired=${r.repaired} markedUnusable=${r.markedUnusable}")
                sb.appendLine("     ${r.description}")
            }
        }
        sb.appendLine()
        sb.appendLine("Persistent sector map (policy: UNHEALTHY unless verified healthy; crash/power-off => PERMANENTLY UNUSABLE, never rescanned):")
        val summary = sectorMapStore.summary()
        if (summary.isEmpty()) {
            sb.appendLine("  No sector map entries yet.")
        } else {
            summary.forEach { (target, counts) ->
                sb.appendLine("  Target: $target")
                counts.forEach { (status, bytes) -> sb.appendLine("    $status: $bytes bytes") }
            }
        }
        sb.appendLine()
        sb.appendLine("Notes:")
        sb.appendLine("- File-level scans read accessible files and perform bit-pattern write/read verification on accessible storage.")
        sb.appendLine("- Raw block scans require root and read blocks via su/dd. This app does not write to raw block devices.")
        sb.appendLine("- Physical bad-sector remapping is handled by flash storage controller/firmware; software cannot reliably repair it.")
        sb.appendLine("- Android apps cannot force other apps to use specific physical sectors; OS/filesystem-level enforcement requires root/system support.")
        return sb.toString()
    }

    private fun updateUi(p: ScanProgress) {
        lastProgress = p
        binding.statusText.text = p.message
        binding.progressBar.progress = p.progressPercent
        binding.healthText.text = "Health: ${p.health}"
        val colorRes = when (p.health) {
            HealthStatus.HEALTHY -> R.color.status_ok
            HealthStatus.DEGRADED -> R.color.status_warn
            HealthStatus.CRITICAL -> R.color.status_bad
            HealthStatus.UNKNOWN -> R.color.md_theme_secondary
        }
        binding.healthText.setTextColor(ContextCompat.getColor(this, colorRes))
        val mapSummary = sectorMapStore.summary()
        var healthyBytes = 0L
        var badPermBytes = 0L
        mapSummary.values.forEach { m ->
            healthyBytes += m[com.buboadonis.s7health.util.SectorStatus.HEALTHY] ?: 0L
            badPermBytes += m[com.buboadonis.s7health.util.SectorStatus.BAD_PERMANENT] ?: 0L
        }
        binding.statsText.text =
            "Scanned: ${RawBlockScanner.formatBytes(p.bytesScanned)}\n" +
            "Verified: ${RawBlockScanner.formatBytes(p.verifiedBytes)}\n" +
            "Read errors: ${p.readErrors}\n" +
            "Write errors: ${p.writeErrors}\n" +
            "Bad regions: ${p.badRegions.size}\n" +
            "Speed: ${RawBlockScanner.formatBytes(p.speedBytesPerSec)}/s\n" +
            "Map healthy: ${RawBlockScanner.formatBytes(healthyBytes)}\n" +
            "Map bad-perm: ${RawBlockScanner.formatBytes(badPermBytes)}\n" +
            "Unverified: UNHEALTHY by policy"

        val running = p.state == ScanState.SCANNING || p.state == ScanState.REPAIRING
        binding.btnSmartScan.isEnabled = !running
        binding.btnStop.isEnabled = running
    }

    private fun appendLog(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "[$ts] $msg"
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > maxLogLines) logLines.removeFirst()
        }
        binding.logText.text = logLines.joinToString("\n")
    }

    private fun appendLogUi(msg: String) {
        runOnUiThread { appendLog(msg) }
    }

    private fun viewBadSegments() {
        val summary = sectorMapStore.summary()
        val sb = StringBuilder()
        sb.appendLine("Policy: UNVERIFIED=UNHEALTHY by default. BAD_PERMANENT sectors are never rescanned.")
        sb.appendLine()
        if (summary.isEmpty()) {
            sb.appendLine("No sector map data yet. Run a scan to build the map.")
        } else {
            summary.forEach { (target, counts) ->
                sb.appendLine("Target: $target")
                counts.forEach { (status, bytes) ->
                    sb.appendLine("  $status: ${RawBlockScanner.formatBytes(bytes)}")
                }
                val bads = sectorMapStore.badRanges(target)
                bads.take(50).forEach { r ->
                    sb.appendLine("  - BAD [offset=${r.offset}, len=${r.length}]")
                }
                if (bads.size > 50) sb.appendLine("  ... ${bads.size - 50} more")
                sb.appendLine()
            }
        }
        sb.appendLine("Last scan bad regions: ${lastProgress.badRegions.size}")
        lastProgress.badRegions.take(30).forEach { r ->
            sb.appendLine("- ${r.pathOrDevice} @${r.offsetBytes}+${r.lengthBytes} ${r.description}")
        }
        AlertDialog.Builder(this)
            .setTitle("Problematic segments")
            .setMessage(sb.toString().take(4000))
            .setPositiveButton("OK", null)
            .setNeutralButton("Mark scan bad as permanent") { _, _ -> markScanBadAsPermanent() }
            .show()
    }

    private fun markScanBadAsPermanent() {
        val bads = lastProgress.badRegions.filter { it.lengthBytes > 0 }
        if (bads.isEmpty()) { toast("No bad regions with known size to mark."); return }
        var marked = 0
        bads.forEach { r ->
            sectorMapStore.mark(r.pathOrDevice, r.offsetBytes, r.lengthBytes, com.buboadonis.s7health.util.SectorStatus.BAD_PERMANENT)
            marked++
        }
        sectorMapStore.save()
        appendLog("Marked $marked bad region(s) as PERMANENTLY UNUSABLE in sector map. They will not be rescanned.")
        toast("Marked $marked region(s) as permanently unusable.")
    }

    private fun setPremiumMode(enabled: Boolean) {
        getSharedPreferences("s7health", MODE_PRIVATE).edit().putBoolean("premium_mode", enabled).apply()
        binding.premiumActions.visibility = if (enabled) android.view.View.VISIBLE else android.view.View.GONE
        appendLog(if (enabled) "Premium Root Mode enabled." else "Premium Root Mode disabled.")
    }

    private fun applyOnUnmountedFlow() {
        lifecycleScope.launch {
            val rootOk = withContext(Dispatchers.IO) { repairManager.isRootAvailable() }
            if (!rootOk) { toast("Root required."); return@launch }
            val targets = sectorMapStore.summary().keys.toList()
            if (targets.isEmpty()) { toast("No sector map data."); return@launch }
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Apply on UNMOUNTED target")
                .setItems(targets.toTypedArray()) { _, which -> confirmApplyBadBlocks(targets[which]) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun exportMagiskBootScript() {
        val targets = sectorMapStore.summary().keys.toList()
        if (targets.isEmpty()) { toast("No sector map data yet. Run a scan first."); return }
        AlertDialog.Builder(this)
            .setTitle("Export Magisk boot script")
            .setItems(targets.toTypedArray()) { _, which ->
                val target = targets[which]
                lifecycleScope.launch {
                    val file = withContext(Dispatchers.IO) { repairManager.exportMagiskBootScript(target) }
                    if (file != null) {
                        appendLog("Magisk boot script exported: ${file.absolutePath}")
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Magisk boot script exported")
                            .setMessage("Target: $target\nScript: ${file.absolutePath}\n\nInstall manually to /data/adb/service.d/ (Magisk) and chmod +x. The script only applies when the target is UNMOUNTED. Do NOT use on critical mounted system/data partitions.")
                            .setPositiveButton("Install via root") { _, _ -> installMagiskScript(file) }
                            .setNegativeButton("OK", null)
                            .show()
                    } else toast("Export failed.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun installMagiskScript(script: File) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { repairManager.installMagiskScript(script) }
            appendLog(result.message)
            AlertDialog.Builder(this@MainActivity)
                .setTitle(if (result.ok) "Installed" else "Install failed")
                .setMessage(result.message)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun exportRecoveryScript() {
        val targets = sectorMapStore.summary().keys.toList()
        if (targets.isEmpty()) { toast("No sector map data yet. Run a scan first."); return }
        AlertDialog.Builder(this)
            .setTitle("Export recovery script")
            .setItems(targets.toTypedArray()) { _, which ->
                val target = targets[which]
                lifecycleScope.launch {
                    val file = withContext(Dispatchers.IO) { repairManager.exportRecoveryScript(target) }
                    if (file != null) {
                        appendLog("Recovery script exported: ${file.absolutePath}")
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Recovery script exported")
                            .setMessage("Target: $target\nScript: ${file.absolutePath}\n\nRun this in recovery/TWRP/adb shell ONLY when the partition is UNMOUNTED. It will apply bad-block tagging (ext2/3/4 via e2fsck -l).")
                            .setPositiveButton("OK", null)
                            .show()
                    } else toast("Export failed.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportBadBlocksMap() {
        val targets = sectorMapStore.summary().keys.toList()
        if (targets.isEmpty()) {
            toast("No sector map data yet. Run a scan first.")
            return
        }
        val labels = targets.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Export bad blocks map")
            .setItems(labels) { _, which ->
                val target = targets[which]
                val file = repairManager.exportBadBlocksFile(target)
                if (file != null) {
                    appendLog("Bad blocks map exported: ${file.absolutePath}")
                    AlertDialog.Builder(this)
                        .setTitle("Bad blocks exported")
                        .setMessage("Target: $target\nFile: ${file.absolutePath}\n\nThis file can be used with e2fsck -l on an UNMOUNTED ext2/3/4 partition (root required).")
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    toast("Export failed.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyBadBlocksFlow() {
        lifecycleScope.launch {
            val rootOk = withContext(Dispatchers.IO) { repairManager.isRootAvailable() }
            if (!rootOk) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Root required")
                    .setMessage("Tagging bad sectors at filesystem/block level requires root (su). Without root, the app can only keep its own sector map and quarantine accessible files.")
                    .setPositiveButton("OK", null)
                    .show()
                return@launch
            }
            val targets = sectorMapStore.summary().keys.toList()
            if (targets.isEmpty()) { toast("No sector map data. Run a scan first."); return@launch }
            val labels = targets.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Select target to tag")
                .setItems(labels) { _, which -> confirmApplyBadBlocks(targets[which]) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun confirmApplyBadBlocks(target: String) {
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                val mounted = repairManager.isMounted(target)
                val fs = repairManager.detectFsType(target)
                val mountInfo = repairManager.getMountInfo(target)
                Triple(mounted, fs, mountInfo)
            }
            val (mounted, fs, mountInfo) = info
            val badFile = repairManager.exportBadBlocksFile(target)
            if (badFile == null) { toast("Failed to export bad blocks file."); return@launch }
            val msg = buildString {
                append("Target: $target\nFS: $fs\nMounted: $mounted\n")
                if (mountInfo.isNotBlank()) append("Mount: $mountInfo\n")
                append("\nBad blocks file: ${badFile.absolutePath}\n\n")
                append("WARNING: This attempts to tag bad blocks so the filesystem/OS avoids them. ")
                append("It is only safe on an UNMOUNTED partition. Do NOT run on mounted system/data partitions. ")
                append("ext2/3/4: uses e2fsck -f -y -l. F2FS/others: limited/unsupported. ")
                append("Physical flash remapping is controller-controlled; this is best-effort filesystem tagging.\n\n")
                append("Proceed only if you understand the risk.")
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Tag bad sectors (root)")
                .setMessage(msg)
                .setPositiveButton(if (mounted) "Mounted - cannot apply" else "Apply") { _, _ ->
                    if (mounted) {
                        toast("Device is mounted; unmount first.")
                    } else {
                        runApplyBadBlocks(target, badFile)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun runApplyBadBlocks(target: String, badFile: File) {
        appendLog("Applying bad blocks to $target using ${badFile.name} ...")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { repairManager.applyBadBlocks(target, badFile) }
            appendLog(result.message)
            if (result.output.isNotBlank()) appendLog(result.output.take(2000))
            AlertDialog.Builder(this@MainActivity)
                .setTitle(if (result.ok) "Tagging complete" else "Tagging failed / unsupported")
                .setMessage(result.message + if (result.output.isNotBlank()) "\n\n" + result.output.take(1500) else "")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
