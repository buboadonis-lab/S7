package com.buboadonis.s7health.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import com.buboadonis.s7health.model.ScanProgress
import com.buboadonis.s7health.model.ScanState
import com.buboadonis.s7health.scanner.RawBlockScanner
import com.buboadonis.s7health.scanner.StorageScanner
import com.buboadonis.s7health.util.CheckpointStore
import com.buboadonis.s7health.util.ScanCheckpoint
import com.buboadonis.s7health.util.SectorMapStore
import com.buboadonis.s7health.model.ScanMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val storageScanner = StorageScanner()
    private val rawScanner = RawBlockScanner()
    private lateinit var checkpointStore: CheckpointStore
    private lateinit var sectorMapStore: SectorMapStore
    private var scanJob: Job? = null
    private var lastProgress: ScanProgress = ScanProgress()
    private val logLines = ArrayDeque<String>()
    private val maxLogLines = 300

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> appendLog("Permissions updated. You can start a scan.") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnScanStorage.setOnClickListener { startStorageScan() }
        binding.btnScanRaw.setOnClickListener { startRawScanFlow() }
        binding.btnStop.setOnClickListener { stopScan() }
        binding.btnRepair.setOnClickListener { repairOrMark() }
        binding.btnExport.setOnClickListener { exportReport() }

        checkpointStore = CheckpointStore(this)
        sectorMapStore = SectorMapStore(this)
        updateUi(lastProgress)
        ensurePermissions()
        appendLog("S7 Health Scanner ready. Target device: Samsung Galaxy S7 Edge (or any Android device).")
        appendLog("File-level scan works without root. Raw block scan requires root.")
        checkForResume()
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
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            appendLog("For broad storage scans on Android 11+, grant 'All files access' in settings.")
        }
    }

    private fun startStorageScan() {
        if (scanJob?.isActive == true) {
            toast("A scan is already running.")
            return
        }
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
                .setNegativeButton("Limited scan") { _, _ -> launchStorageScan() }
                .show()
            return
        }
        launchStorageScan()
    }

    private fun launchStorageScan(resume: ScanCheckpoint? = null, overrideRoots: List<File>? = null) {
        val roots = overrideRoots ?: accessibleRoots()
        if (roots.isEmpty()) {
            toast("No accessible storage roots found.")
            return
        }
        if (resume == null) checkpointStore.clear()
        appendLog((if (resume != null) "Resuming" else "Starting") + " file-level scan on: ${roots.joinToString { it.absolutePath }}")
        scanJob = lifecycleScope.launch {
            val result = storageScanner.scanAccessible(
                roots = roots,
                patternTestMb = 16,
                resume = resume,
                onCheckpoint = { checkpointStore.save(it) }
            ) { progress ->
                runOnUiThread { updateUi(progress) }
            }
            lastProgress = result
            updateUi(result)
            if (result.state == ScanState.COMPLETED) checkpointStore.clear()
            appendLog(result.message)
        }
    }

    private fun startRawScanFlow() {
        if (scanJob?.isActive == true) {
            toast("A scan is already running.")
            return
        }
        appendLog("Checking root access...")
        lifecycleScope.launch {
            val rootAvailable = withContext(Dispatchers.IO) { rawScanner.isRootAvailable() }
            if (!rootAvailable) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Root not available")
                    .setMessage("Raw sector/block scanning requires root (su). On a non-rooted device, use the file-level scan instead.\n\nYou can still run file-level scans which verify readable files and perform bit-pattern write/read tests on accessible storage.")
                    .setPositiveButton("OK", null)
                    .show()
                appendLog("Root not available. Raw scan unavailable.")
                return@launch
            }
            val devices = withContext(Dispatchers.IO) { rawScanner.listBlockDevices() }
            if (devices.isEmpty()) {
                toast("No block devices found.")
                appendLog("No block devices enumerated.")
                return@launch
            }
            val labels = devices.map {
                val size = if (it.sizeBytes > 0) RawBlockScanner.formatBytes(it.sizeBytes) else "unknown size"
                "${it.name} ($size) - ${it.path}"
            }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Select block device")
                .setItems(labels) { _, which ->
                    confirmRawScan(devices[which])
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun confirmRawScan(device: RawBlockScanner.BlockDevice) {
        val sizeText = if (device.sizeBytes > 0) RawBlockScanner.formatBytes(device.sizeBytes) else "unknown size"
        AlertDialog.Builder(this)
            .setTitle("Raw scan")
            .setMessage(
                "Scan ${device.name} (${device.path}, $sizeText)?\n\n" +
                "This performs read-only sector/block reads. It may take a long time on large devices. " +
                "No data is written during scan. Progress is checkpointed before each chunk so you can resume if the device restarts. " +
                "If the device is the active system partition, scan it only if you understand the risk of I/O stress."
            )
            .setPositiveButton("Scan") { _, _ -> launchRawScan(device) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launchRawScan(device: RawBlockScanner.BlockDevice, resume: ScanCheckpoint? = null) {
        if (resume == null) checkpointStore.clear()
        appendLog((if (resume != null) "Resuming" else "Starting") + " raw scan: ${device.path}")
        scanJob = lifecycleScope.launch {
            val result = rawScanner.scanBlockDevice(
                device = device,
                resume = resume,
                sectorMap = sectorMapStore,
                onCheckpoint = { checkpointStore.save(it) }
            ) { progress ->
                runOnUiThread { updateUi(progress) }
            }
            lastProgress = result
            updateUi(result)
            if (result.state == ScanState.COMPLETED) checkpointStore.clear()
            appendLog(result.message)
        }
    }

    private fun stopScan() {
        if (scanJob?.isActive == true) {
            scanJob?.cancel()
            appendLog("Cancellation requested.")
        } else {
            toast("No scan running.")
        }
    }

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
        binding.btnScanStorage.isEnabled = !running
        binding.btnScanRaw.isEnabled = !running
        binding.btnRepair.isEnabled = !running
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

    private fun checkForResume() {
        val cp = checkpointStore.load() ?: return
        val ageMin = (System.currentTimeMillis() - cp.timestamp) / 60000L
        val modeLabel = if (cp.mode == ScanMode.FILE_LEVEL) "File-level scan" else "Raw scan (${cp.target})"
        AlertDialog.Builder(this)
            .setTitle("Resume previous scan?")
            .setMessage("$modeLabel\nProgress: ${RawBlockScanner.formatBytes(cp.bytesScanned)} scanned, ${cp.badRegions.size} issue(s) found.\nSaved ${ageMin} min ago.\n\nResuming avoids re-scanning from the beginning after a restart/crash.")
            .setPositiveButton("Resume") { _, _ -> resumeScan(cp) }
            .setNegativeButton("Discard") { _, _ -> checkpointStore.clear() }
            .show()
    }

    private fun resumeScan(cp: ScanCheckpoint) {
        if (scanJob?.isActive == true) { toast("A scan is already running."); return }
        when (cp.mode) {
            ScanMode.FILE_LEVEL -> {
                val roots = cp.roots.map { File(it) }.filter { it.exists() }.ifEmpty { accessibleRoots() }
                appendLog("Resuming file-level scan from checkpoint (${cp.fileIndex} files, ${RawBlockScanner.formatBytes(cp.bytesScanned)} scanned)...")
                launchStorageScan(resume = cp, overrideRoots = roots)
            }
            ScanMode.RAW_BLOCK -> {
                val device = RawBlockScanner.BlockDevice(cp.target, File(cp.target).name, cp.rawTotalBytes)
                appendLog("Resuming raw scan on ${cp.target} from offset ${cp.rawOffsetBytes}...")
                launchRawScan(device, resume = cp)
            }
        }
    }

    private fun accessibleRoots(): List<File> {
        val roots = mutableListOf<File>()
        // App-specific external dirs are always accessible
        getExternalFilesDir(null)?.let { roots.add(it) }
        filesDir?.let { roots.add(it) }
        // Public storage
        val ext = Environment.getExternalStorageDirectory()
        if (ext != null && ext.exists()) roots.add(ext)
        // Common S7 paths
        listOf("/storage/emulated/0", "/sdcard").forEach { p ->
            val f = File(p)
            if (f.exists()) roots.add(f)
        }
        return roots.distinctBy { it.absolutePath }.filter { it.exists() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
