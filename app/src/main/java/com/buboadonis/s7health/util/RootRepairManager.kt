package com.buboadonis.s7health.util

import android.content.Context
import java.io.File

data class RepairResult(val ok: Boolean, val message: String, val output: String = "")

class RootRepairManager(private val context: Context) {

    fun isRootAvailable(): Boolean = runSu("id").stdout.contains("uid=0")

    fun exportBadBlocksFile(targetDevice: String, blockSize: Int = 4096): File? {
        return try {
            val store = SectorMapStore(context)
            val bad = store.badRanges(targetDevice)
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val safeName = targetDevice.replace("/", "_").trim('_')
            val out = File(dir, "badblocks_${safeName}_${blockSize}.txt")
            out.bufferedWriter().use { w ->
                w.write("# S7 Health Scanner bad blocks export\n")
                w.write("# target=$targetDevice blockSize=$blockSize\n")
                w.write("# Default policy: unverified=UNHEALTHY; crash/power-off during scan=PERMANENTLY UNUSABLE\n")
                for (r in bad) {
                    val start = r.offset / blockSize
                    val end = (r.offset + r.length - 1) / blockSize
                    var b = start
                    while (b <= end) { w.write(b.toString()); w.write("\n"); b++ }
                }
            }
            out
        } catch (_: Throwable) { null }
    }

    fun getMountInfo(device: String): String = runSu("cat /proc/mounts | grep '${device.replace("'","")}' || true").stdout.trim()

    fun isMounted(device: String): Boolean {
        val mounts = runSu("cat /proc/mounts").stdout
        return mounts.lines().any { line ->
            val parts = line.split(" ")
            parts.isNotEmpty() && (parts[0] == device || parts[0].endsWith("/${File(device).name}"))
        }
    }

    fun detectFsType(device: String): String {
        val blkid = runSu("blkid -o value -s TYPE $device 2>/dev/null || blkid $device 2>/dev/null").stdout.trim()
        if (blkid.isNotEmpty() && !blkid.contains(" ")) return blkid.lineSequence().first().trim()
        // fallback parse
        val m = Regex("TYPE=\"?([A-Za-z0-9]+)\"?").find(blkid)
        return m?.groupValues?.getOrNull(1) ?: "unknown"
    }

    fun applyBadBlocks(device: String, badBlocksFile: File): RepairResult {
        if (!isRootAvailable()) return RepairResult(false, "Root (su) not available.")
        if (!badBlocksFile.exists()) return RepairResult(false, "Bad blocks file not found.")
        if (isMounted(device)) {
            return RepairResult(false, "Device is mounted. Unmount it before applying bad blocks. Applying on a mounted filesystem is unsafe.", getMountInfo(device))
        }
        val fs = detectFsType(device).lowercase()
        return when {
            fs == "ext2" || fs == "ext3" || fs == "ext4" -> {
                val cmd = "e2fsck -f -y -l ${badBlocksFile.absolutePath} $device 2>&1; echo __EXIT:$?"
                val r = runSu(cmd, timeoutMs = 300_000)
                val ok = r.stdout.contains("__EXIT:0") || r.stdout.contains("__EXIT:1") // e2fsck returns 1 if corrected
                RepairResult(ok, if (ok) "e2fsck completed (fs=$fs). Review output." else "e2fsck failed (fs=$fs).", r.stdout)
            }
            fs == "f2fs" -> {
                // f2fs doesn't support -l badblocks the same way; fsck.f2fs -a is limited.
                val r = runSu("fsck.f2fs -a $device 2>&1; echo __EXIT:$?", timeoutMs = 300_000)
                val ok = r.stdout.contains("__EXIT:0")
                RepairResult(ok, "F2FS does not support standard badblocks list tagging like ext4. Ran fsck.f2fs -a (best effort).", r.stdout)
            }
            else -> {
                RepairResult(false, "Filesystem '$fs' is not supported for bad-block tagging by this app. Bad blocks file was exported; apply manually with appropriate tools on an unmounted volume.", "fs=$fs")
            }
        }
    }

    data class CmdResult(val exitCode: Int, val stdout: String)
    fun runSu(command: String, timeoutMs: Long = 30_000): CmdResult {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val stdout = StringBuilder()
            val err = StringBuilder()
            val tOut = Thread { try { p.inputStream.bufferedReader().use { stdout.append(it.readText()) } } catch (_: Throwable) {} }
            val tErr = Thread { try { p.errorStream.bufferedReader().use { err.append(it.readText()) } } catch (_: Throwable) {} }
            tOut.start(); tErr.start()
            val worker = Thread { try { p.waitFor() } catch (_: Throwable) {} }
            worker.start(); worker.join(timeoutMs)
            if (worker.isAlive) {
                try { p.destroy() } catch (_: Throwable) {}
                try { p.destroyForcibly() } catch (_: Throwable) {}
                worker.join(1000); tOut.join(1000); tErr.join(1000)
                return CmdResult(-1, stdout.toString() + "\n" + err.toString() + "\nTIMEOUT")
            }
            tOut.join(1000); tErr.join(1000)
            val exit = try { p.exitValue() } catch (_: Throwable) { -1 }
            CmdResult(exit, stdout.toString().ifBlank { err.toString() })
        } catch (t: Throwable) { CmdResult(-1, t.message ?: "error") }
    }

    fun exportRecoveryScript(targetDevice: String, blockSize: Int = 4096): File? {
        return try {
            val badFile = exportBadBlocksFile(targetDevice, blockSize) ?: return null
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val safeName = targetDevice.replace("/", "_").trim('_')
            val out = File(dir, "apply_badblocks_${safeName}.sh")
            val fs = detectFsType(targetDevice).lowercase().ifBlank { "ext4" }
            out.writeText("""#!/system/bin/sh
# S7 Health Scanner recovery script
# TARGET=$targetDevice
# FS=$fs
# BADBLOCKS=${badFile.absolutePath}
# IMPORTANT: Run only on an UNMOUNTED partition (recovery/TWRP/adb shell in recovery).
# Applying on mounted partitions can cause corruption.

echo "Target: $targetDevice"
echo "Filesystem: $fs"
echo "Badblocks: ${badFile.absolutePath}"

# Verify unmounted
if grep -q " $targetDevice " /proc/mounts; then
  echo "ERROR: $targetDevice is mounted. Unmount before proceeding."
  exit 1
fi

case "$fs" in
  ext2|ext3|ext4)
    e2fsck -f -y -l "${badFile.absolutePath}" "$targetDevice"
    ;;
  f2fs)
    echo "F2FS does not support ext badblocks list; running fsck.f2fs -a"
    fsck.f2fs -a "$targetDevice"
    ;;
  *)
    echo "Unsupported fs type: $fs. Badblocks file exported for manual use."
    exit 2
    ;;
esac
""")
            out
        } catch (_: Throwable) { null }
    }

    fun exportMagiskBootScript(targetDevice: String, blockSize: Int = 4096): File? {
        return try {
            val badFile = exportBadBlocksFile(targetDevice, blockSize) ?: return null
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val safeName = targetDevice.replace("/", "_").trim('_')
            val out = File(dir, "service.d_s7health_${safeName}.sh")
            val fs = detectFsType(targetDevice).lowercase().ifBlank { "ext4" }
            val ds = '$'
            out.writeText("""#!/system/bin/sh
# S7 Health Scanner - Magisk service.d script (exported)
# Target: $targetDevice  FS: $fs
# SAFETY: Only runs if target is UNMOUNTED. Do NOT use on mounted system/data.
BADBLOCKS="${badFile.absolutePath}"
TARGET="$targetDevice"
FS="$fs"
LOG="/data/local/tmp/s7health_badblocks.log"

echo "${ds}(date): s7health boot script start" >> "${ds}LOG"
if grep -q " ${ds}TARGET " /proc/mounts; then
  echo "${ds}(date): SKIP ${ds}TARGET is mounted" >> "${ds}LOG"
  exit 0
fi
case "${ds}FS" in
  ext2|ext3|ext4)
    e2fsck -f -y -l "${ds}BADBLOCKS" "${ds}TARGET" >> "${ds}LOG" 2>&1
    ;;
  *)
    echo "${ds}(date): unsupported fs ${ds}FS" >> "${ds}LOG"
    ;;
esac
echo "${ds}(date): done" >> "${ds}LOG"
""")
            out
        } catch (_: Throwable) { null }
    }

    fun installMagiskScript(script: File): RepairResult {
        if (!isRootAvailable()) return RepairResult(false, "Root not available.")
        val destDir = "/data/adb/service.d"
        val dest = "$destDir/${script.name}"
        val cmd = "mkdir -p $destDir && cp '${script.absolutePath}' '$dest' && chmod 755 '$dest' && echo __EXIT:$?"
        val r = runSu(cmd, timeoutMs = 20_000)
        val ok = r.stdout.contains("__EXIT:0")
        return RepairResult(ok, if (ok) "Installed to $dest. It will run at boot via Magisk service.d (only when target is unmounted)." else "Install failed: ${r.stdout.take(500)}", r.stdout)
    }
}
