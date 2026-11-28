package com.buboadonis.s7health.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.buboadonis.s7health.R
import com.buboadonis.s7health.model.HealthStatus
import com.buboadonis.s7health.model.ScanMode
import com.buboadonis.s7health.model.ScanProgress
import com.buboadonis.s7health.model.ScanState
import com.buboadonis.s7health.scanner.RawBlockScanner
import com.buboadonis.s7health.scanner.StorageScanner
import com.buboadonis.s7health.util.CheckpointStore
import com.buboadonis.s7health.util.RootRepairManager
import com.buboadonis.s7health.util.ScanCheckpoint
import com.buboadonis.s7health.util.SectorMapStore
import com.buboadonis.s7health.util.SectorStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that runs scans in the background, survives screen-off
 * via PARTIAL_WAKE_LOCK, and auto-resumes after crash/reboot.
 *
 * Auto-determines the best scan mode:
 *  - If a checkpoint exists, resume it (no user prompt).
 *  - Else if root is available, raw block scan on the largest block device.
 *  - Else file-level scan on accessible roots.
 *
 * After scan completion, auto-executes appropriate actions:
 *  - File scan: quarantine bad files, mark unusable, export report.
 *  - Raw scan: export badblocks, attempt tagging if unmounted ext4, export report.
 */
class ScanService : Service() {

    companion object {
        const val ACTION_START = "com.buboadonis.s7health.action.START"
        const val ACTION_STOP = "com.buboadonis.s7health.action.STOP"
        const val ACTION_RESUME = "com.buboadonis.s7health.action.RESUME"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "s7health_scan"
        private const val WAKE_TAG = "S7Health::ScanWakeLock"

        @JvmStatic
        fun start(context: Context, action: String = ACTION_START) {
            val intent = Intent(context, ScanService::class.java).apply {
                this.action = action
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            val intent = Intent(context, ScanService::class.java).apply {
                this.action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var checkpointStore: CheckpointStore
    private lateinit var sectorMapStore: SectorMapStore
    private lateinit var repairManager: RootRepairManager
    private val storageScanner = StorageScanner()
    private val rawScanner = RawBlockScanner()

    override fun onCreate() {
        super.onCreate()
        checkpointStore = CheckpointStore(this)
        sectorMapStore = SectorMapStore(this)
        repairManager = RootRepairManager(this)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_STOP -> {
                scanJob?.cancel()
                releaseWakeLock()
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, ACTION_RESUME, null -> {
                // Always start foreground immediately to avoid FGS timeout
                startForeground(NOTIFICATION_ID, buildNotification("Starting scan..."))
                acquireWakeLock()
                if (scanJob?.isActive != true) {
                    scanJob = scope.launch { runSmartScan() }
                }
            }
        }
        // START_STICKY so system restarts us after crash/kill; on restart we resume from checkpoint
        return START_STICKY
    }

    override fun onDestroy() {
        scanJob?.cancel()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "S7 Health Scan",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows scan progress. Keeps scanning when screen is off."
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("S7 Health Scanner")
            .setContentText(text)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Throwable) {}
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
                setReferenceCounted(false)
                // 12 hours max; released on completion/stop
                acquire(12 * 60 * 60 * 1000L)
            }
        } catch (_: Throwable) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Throwable) {}
        wakeLock = null
    }

    private suspend fun runSmartScan() {
        try {
            val cp = checkpointStore.load()
            if (cp != null) {
                // Auto-resume without prompting
                when (cp.mode) {
                    ScanMode.FILE_LEVEL -> runFileScan(resume = cp)
                    ScanMode.RAW_BLOCK -> runRawScan(resume = cp)
                }
                return
            }

            // No checkpoint: auto-determine best mode
            val rootAvailable = withContext(Dispatchers.IO) { rawScanner.isRootAvailable() }
            if (rootAvailable) {
                val devices = withContext(Dispatchers.IO) { rawScanner.listBlockDevices() }
                // Pick largest device (most likely main storage), prefer mmcblk0 / by-name system/data
                val best = devices
                    .filter { it.sizeBytes > 0 }
                    .maxByOrNull { it.sizeBytes }
                    ?: devices.firstOrNull()
                if (best != null) {
                    runRawScan(device = best, resume = null)
                    return
                }
            }
            // Fallback to file scan
            runFileScan(resume = null)
        } catch (t: Throwable) {
            updateNotification("Scan failed: ${t.message}")
            // Keep checkpoint for resume; do not clear
        } finally {
            // If scan completed, checkpoint already cleared inside run* methods.
            // If still has checkpoint (failed/cancelled), keep it for next auto-resume.
            // Release wake lock only when no checkpoint remains (completed) or explicit stop.
            // For safety, if checkpoint exists we keep service alive briefly then stop;
            // BootReceiver / next start will resume.
            val remaining = checkpointStore.load()
            if (remaining == null) {
                releaseWakeLock()
                stopForeground(true)
                stopSelf()
            } else {
                // Keep foreground so user sees "paused, will resume" and system can restart us
                updateNotification("Scan paused. Will auto-resume. Progress saved.")
                // Release wake lock to save battery but keep service sticky for restart
                releaseWakeLock()
                // Don't stopSelf - let system keep us; but to avoid FGS timeout, stop foreground after a bit?
                // We stop foreground but keep service sticky - system will restart on next boot/crash.
                // For now, stop foreground and stop self; next app launch / boot will resume.
                stopForeground(true)
                stopSelf()
            }
        }
    }

    private suspend fun runFileScan(resume: ScanCheckpoint?) {
        val roots = if (resume != null && resume.roots.isNotEmpty()) {
            resume.roots.map { File(it) }.filter { it.exists() }.ifEmpty { accessibleRoots() }
        } else {
            accessibleRoots()
        }
        if (roots.isEmpty()) {
            updateNotification("No accessible storage found.")
            return
        }
        if (resume == null) checkpointStore.clear()

        val result = storageScanner.scanAccessible(
            roots = roots,
            patternTestMb = 64,
            resume = resume,
            onCheckpoint = { checkpointStore.save(it) }
        ) { progress ->
            updateNotification(progress.message)
            // Persist last progress for UI polling
            saveLastProgress(progress)
        }

        saveLastProgress(result)
        if (result.state == ScanState.COMPLETED) {
            // Auto-actions: quarantine bad files, export report
            autoQuarantineAndReport(result)
            checkpointStore.clear()
            updateNotification("Scan complete. ${result.badRegions.size} issue(s) found.")
        }
    }

    private suspend fun runRawScan(device: RawBlockScanner.BlockDevice? = null, resume: ScanCheckpoint?) {
        val dev = device ?: if (resume != null) {
            RawBlockScanner.BlockDevice(resume.target, File(resume.target).name, resume.rawTotalBytes)
        } else {
            // Should not happen - runSmartScan picks device
            return
        }
        if (resume == null) checkpointStore.clear()

        val result = rawScanner.scanBlockDevice(
            device = dev,
            resume = resume,
            sectorMap = sectorMapStore,
            onCheckpoint = { checkpointStore.save(it) }
        ) { progress ->
            updateNotification(progress.message)
            saveLastProgress(progress)
        }

        saveLastProgress(result)
        if (result.state == ScanState.COMPLETED) {
            // Auto-actions: export badblocks, try tagging if unmounted, export report
            autoTagAndReport(dev.path, result)
            checkpointStore.clear()
            updateNotification("Raw scan complete. ${result.badRegions.size} bad region(s).")
        }
    }

    private suspend fun autoQuarantineAndReport(progress: ScanProgress) {
        try {
            // 1. Quarantine bad files automatically (persistent, survives reboot)
            val badFiles = progress.badRegions
                .filter { !it.markedUnusable && !it.repaired }
                .map { File(it.pathOrDevice) }
                .filter { it.exists() && it.isFile && !it.name.startsWith(".s7health_") }
                .distinctBy { it.absolutePath }

            var quarantinedCount = 0
            if (badFiles.isNotEmpty()) {
                val quarantineDir = File(filesDir, "quarantine").apply { mkdirs() }
                val updated = progress.badRegions.toMutableList()
                val manifestEntries = mutableListOf<org.json.JSONObject>()
                withContext(Dispatchers.IO) {
                    for (f in badFiles) {
                        try {
                            val target = File(quarantineDir, f.name + ".quarantined-" + System.currentTimeMillis())
                            val ok = f.renameTo(target) || copyThenDelete(f, target)
                            if (ok) {
                                quarantinedCount++
                                val idx = updated.indexOfFirst { it.pathOrDevice == f.absolutePath }
                                if (idx >= 0) {
                                    updated[idx] = updated[idx].copy(
                                        markedUnusable = true,
                                        description = updated[idx].description + " [auto-quarantined to ${target.absolutePath}]"
                                    )
                                }
                                val entry = org.json.JSONObject()
                                entry.put("originalPath", f.absolutePath)
                                entry.put("quarantinePath", target.absolutePath)
                                entry.put("timestamp", System.currentTimeMillis())
                                entry.put("reason", "High-precision scan: checksum mismatch or read error")
                                manifestEntries.add(entry)
                            }
                        } catch (_: Throwable) {}
                    }
                    // Persist quarantine manifest (survives reboot, other apps cannot access app-private quarantine)
                    try {
                        val manifestFile = File(filesDir, "quarantine_manifest.json")
                        val existing = if (manifestFile.exists()) {
                            try { org.json.JSONArray(manifestFile.readText()) } catch (_: Throwable) { org.json.JSONArray() }
                        } else org.json.JSONArray()
                        manifestEntries.forEach { existing.put(it) }
                        val tmp = File(manifestFile.parentFile, manifestFile.name + ".tmp")
                        java.io.FileOutputStream(tmp).use { fos ->
                            fos.write(existing.toString().toByteArray(Charsets.UTF_8))
                            fos.flush(); fos.fd.sync()
                        }
                        if (!tmp.renameTo(manifestFile)) { manifestFile.writeText(existing.toString()); tmp.delete() }
                    } catch (_: Throwable) {}
                }
                val newProgress = progress.copy(badRegions = updated)
                saveLastProgress(newProgress)
            }

            // 2. Save isolation status (direct, visible in main UI, persists after restart)
            saveIsolationStatus(
                mode = "FILE_LEVEL",
                target = "accessible storage",
                quarantinedFiles = quarantinedCount,
                badSectors = 0,
                badblocksFile = "",
                taggingStatus = if (quarantinedCount > 0) "Quarantined $quarantinedCount file(s) to app-private storage (inaccessible to other apps, survives reboot)" else "No files to quarantine"
            )

            // 3. Auto-export report
            exportReportFile(progress)
        } catch (_: Throwable) {}
    }

    private suspend fun autoTagAndReport(target: String, progress: ScanProgress) {
        try {
            // 1. Export badblocks file automatically (persistent)
            val badFile = withContext(Dispatchers.IO) { repairManager.exportBadBlocksFile(target) }
            var taggingStatus = "No bad blocks to tag"
            var badSectors = progress.badRegions.size
            if (badFile != null && badSectors > 0) {
                // 2. Export recovery + Magisk scripts automatically (direct, not hidden in Advanced)
                withContext(Dispatchers.IO) {
                    try { repairManager.exportRecoveryScript(target) } catch (_: Throwable) {}
                    try { repairManager.exportMagiskBootScript(target) } catch (_: Throwable) {}
                }
                // 3. If unmounted and ext4, attempt tagging automatically (safe path only)
                val mounted = withContext(Dispatchers.IO) { repairManager.isMounted(target) }
                if (!mounted) {
                    val result = withContext(Dispatchers.IO) { repairManager.applyBadBlocks(target, badFile) }
                    taggingStatus = if (result.ok) "Tagged $badSectors bad block(s) via e2fsck -l (unmounted, persistent)" else "Tagging attempted but failed: ${result.message.take(200)}"
                    if (result.ok) updateNotification("Bad blocks tagged on $target")
                } else {
                    taggingStatus = "Bad blocks exported ($badSectors). Target is MOUNTED - tagging deferred. Will auto-apply on next boot when unmounted (via Magisk script) or in recovery. Bad sectors are recorded in persistent sector map and will not be rescanned."
                }
            } else if (badSectors == 0) {
                taggingStatus = "No bad sectors found - storage verified healthy (2-pass checksum)"
            }
            // 4. Save isolation status (direct, visible, persists after restart)
            saveIsolationStatus(
                mode = "RAW_BLOCK",
                target = target,
                quarantinedFiles = 0,
                badSectors = badSectors,
                badblocksFile = badFile?.absolutePath ?: "",
                taggingStatus = taggingStatus
            )
            // 5. Export report
            exportReportFile(progress)
        } catch (_: Throwable) {}
    }

    private fun saveIsolationStatus(mode: String, target: String, quarantinedFiles: Int, badSectors: Int, badblocksFile: String, taggingStatus: String) {
        try {
            val json = org.json.JSONObject()
            json.put("mode", mode)
            json.put("target", target)
            json.put("quarantinedFiles", quarantinedFiles)
            json.put("badSectors", badSectors)
            json.put("badblocksFile", badblocksFile)
            json.put("taggingStatus", taggingStatus)
            json.put("timestamp", System.currentTimeMillis())
            json.put("accuracy", "High-precision 2-pass SHA-256 verified (targeting 99.9% for detectable logical errors)")
            val file = File(filesDir, "isolation_status.json")
            val tmp = File(file.parentFile, file.name + ".tmp")
            java.io.FileOutputStream(tmp).use { fos ->
                fos.write(json.toString().toByteArray(Charsets.UTF_8))
                fos.flush(); fos.fd.sync()
            }
            if (!tmp.renameTo(file)) { file.writeText(json.toString()); tmp.delete() }
        } catch (_: Throwable) {}
    }

    private fun exportReportFile(p: ScanProgress) {
        try {
            val report = buildReport(p)
            val dir = getExternalFilesDir(null) ?: filesDir
            val name = "s7-health-report-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
            val file = File(dir, name)
            file.writeText(report)
        } catch (_: Throwable) {}
    }

    private fun buildReport(p: ScanProgress): String {
        val sb = StringBuilder()
        sb.appendLine("S7 Health Scanner Report (auto-generated)")
        sb.appendLine("Generated: ${Date()}")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})")
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
        return sb.toString()
    }

    private fun saveLastProgress(p: ScanProgress) {
        // Persist to a small JSON for UI polling (atomic)
        try {
            val json = org.json.JSONObject()
            json.put("state", p.state.name)
            json.put("mode", p.mode.name)
            json.put("bytesScanned", p.bytesScanned)
            json.put("totalBytesEstimate", p.totalBytesEstimate)
            json.put("message", p.message)
            json.put("health", p.health.name)
            json.put("readErrors", p.readErrors)
            json.put("writeErrors", p.writeErrors)
            json.put("verifiedBytes", p.verifiedBytes)
            json.put("speedBytesPerSec", p.speedBytesPerSec)
            json.put("currentTarget", p.currentTarget)
            val badArr = org.json.JSONArray()
            p.badRegions.forEach { b ->
                val o = org.json.JSONObject()
                o.put("pathOrDevice", b.pathOrDevice)
                o.put("offsetBytes", b.offsetBytes)
                o.put("lengthBytes", b.lengthBytes)
                o.put("description", b.description)
                o.put("repaired", b.repaired)
                o.put("markedUnusable", b.markedUnusable)
                badArr.put(o)
            }
            json.put("badRegions", badArr)
            json.put("timestamp", System.currentTimeMillis())
            val file = File(filesDir, "last_progress.json")
            val tmp = File(file.parentFile, file.name + ".tmp")
            java.io.FileOutputStream(tmp).use { fos ->
                fos.write(json.toString().toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                file.writeText(json.toString())
                tmp.delete()
            }
        } catch (_: Throwable) {}
    }

    private fun copyThenDelete(src: File, dst: File): Boolean {
        return try {
            src.inputStream().use { inp -> dst.outputStream().use { out -> inp.copyTo(out) } }
            if (dst.exists() && dst.length() == src.length()) src.delete() else false
        } catch (_: Throwable) { false }
    }

    private fun accessibleRoots(): List<File> {
        val roots = mutableListOf<File>()
        getExternalFilesDir(null)?.let { roots.add(it) }
        filesDir?.let { roots.add(it) }
        val ext = android.os.Environment.getExternalStorageDirectory()
        if (ext != null && ext.exists()) roots.add(ext)
        listOf("/storage/emulated/0", "/sdcard").forEach { p ->
            val f = File(p)
            if (f.exists()) roots.add(f)
        }
        return roots.distinctBy { it.absolutePath }.filter { it.exists() }
    }
}
