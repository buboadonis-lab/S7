package com.buboadonis.s7health.boot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.buboadonis.s7health.R
import com.buboadonis.s7health.service.ScanService
import com.buboadonis.s7health.ui.MainActivity
import com.buboadonis.s7health.util.CheckpointStore
import com.buboadonis.s7health.util.RootRepairManager
import java.io.File
import org.json.JSONObject

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val isBoot = action == Intent.ACTION_BOOT_COMPLETED ||
                action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
                action == Intent.ACTION_MY_PACKAGE_REPLACED ||
                action == "android.intent.action.QUICKBOOT_POWERON"
        if (!isBoot) return

        // Auto-resume scan if checkpoint exists - no user prompt needed
        try {
            val cp = CheckpointStore(context).load()
            if (cp != null) {
                // Start foreground service to resume scanning in background
                ScanService.start(context, ScanService.ACTION_RESUME)
            }
        } catch (_: Throwable) {}

        // Auto-apply pending bad-block tagging if target is now unmounted (persistent isolation after restart)
        try {
            val statusFile = File(context.filesDir, "isolation_status.json")
            if (statusFile.exists()) {
                val json = JSONObject(statusFile.readText())
                val target = json.optString("target")
                val badblocksFile = json.optString("badblocksFile")
                val taggingStatus = json.optString("taggingStatus")
                if (target.isNotBlank() && badblocksFile.isNotBlank() && taggingStatus.contains("MOUNTED")) {
                    val repairManager = RootRepairManager(context)
                    val badFile = File(badblocksFile)
                    if (badFile.exists() && !repairManager.isMounted(target)) {
                        // Run in background thread to avoid blocking receiver
                        Thread {
                            try {
                                val result = repairManager.applyBadBlocks(target, badFile)
                                if (result.ok) {
                                    json.put("taggingStatus", "Tagged on boot (unmounted) via e2fsck -l - persistent")
                                    val tmp = File(statusFile.parentFile, statusFile.name + ".tmp")
                                    java.io.FileOutputStream(tmp).use { fos ->
                                        fos.write(json.toString().toByteArray(Charsets.UTF_8))
                                        fos.flush(); fos.fd.sync()
                                    }
                                    if (!tmp.renameTo(statusFile)) { statusFile.writeText(json.toString()); tmp.delete() }
                                }
                            } catch (_: Throwable) {}
                        }.start()
                    }
                }
            }
        } catch (_: Throwable) {}

        // Notification for visibility
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "s7health_boot"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "S7 Health Guard", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val cpExists = try { CheckpointStore(context).load() != null } catch (_: Throwable) { false }
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("S7 Health Guard active")
            .setContentText(
                if (cpExists) "Resuming scan in background. Tap to view progress."
                else "Ready. Tap Start to scan. Unverified sectors are treated as unhealthy."
            )
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { nm.notify(1001, notification) } catch (_: Throwable) {}
    }
}
