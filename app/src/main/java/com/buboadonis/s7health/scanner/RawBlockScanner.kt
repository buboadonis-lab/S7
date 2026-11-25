package com.buboadonis.s7health.scanner

import com.buboadonis.s7health.model.BadRegion
import com.buboadonis.s7health.model.HealthStatus
import com.buboadonis.s7health.model.ScanMode
import com.buboadonis.s7health.model.ScanProgress
import com.buboadonis.s7health.model.ScanState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.coroutines.coroutineContext
import kotlin.math.max

class RawBlockScanner {

    data class BlockDevice(val path: String, val name: String, val sizeBytes: Long)

    fun isRootAvailable(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            code == 0 && out.contains("uid=0")
        } catch (_: Throwable) {
            false
        }
    }

    fun listBlockDevices(): List<BlockDevice> {
        val devices = mutableListOf<BlockDevice>()
        try {
            // Try /dev/block/by-name
            val byName = runSu("ls -1 /dev/block/by-name 2>/dev/null")
            if (byName.exitCode == 0) {
                byName.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { name ->
                    val realPath = runSu("readlink -f /dev/block/by-name/$name").stdout.trim()
                        .ifEmpty { "/dev/block/by-name/$name" }
                    val size = blockSizeBytes(realPath)
                    devices.add(BlockDevice(realPath, name, size))
                }
            }
            // Also add common mmcblk devices
            val partitions = runSu("cat /proc/partitions 2>/dev/null")
            if (partitions.exitCode == 0) {
                partitions.stdout.lines().drop(2).forEach { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 4) {
                        val name = parts[3]
                        if (name.startsWith("mmcblk") || name.startsWith("sda") || name.startsWith("dm-")) {
                            val blocks = parts[2].toLongOrNull() ?: 0L
                            val path = "/dev/block/$name"
                            if (devices.none { it.path == path }) {
                                devices.add(BlockDevice(path, name, blocks * 1024L))
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) { }
        return devices.distinctBy { it.path }.sortedBy { it.name }
    }

    private fun blockSizeBytes(path: String): Long {
        val r = runSu("blockdev --getsize64 $path 2>/dev/null")
        val v = r.stdout.trim().toLongOrNull()
        if (v != null && v > 0) return v
        // fallback: /sys/class/block
        val name = File(path).name
        val sys = runSu("cat /sys/class/block/$name/size 2>/dev/null")
        val sectors = sys.stdout.trim().toLongOrNull() ?: return 0L
        return sectors * 512L
    }

    suspend fun scanBlockDevice(
        device: BlockDevice,
        maxBytes: Long = device.sizeBytes.takeIf { it > 0 } ?: (256L * 1024 * 1024),
        onProgress: (ScanProgress) -> Unit
    ): ScanProgress = withContext(Dispatchers.IO) {
        val badRegions = mutableListOf<BadRegion>()
        var bytesScanned = 0L
        var readErrors = 0L
        val start = System.currentTimeMillis()
        val chunkSize = 1024 * 1024L // 1MB chunks
        val total = if (device.sizeBytes > 0) minOf(device.sizeBytes, maxBytes) else maxBytes

        fun emit(state: ScanState, msg: String) {
            val elapsed = max(1L, System.currentTimeMillis() - start)
            onProgress(
                ScanProgress(
                    state = state,
                    mode = ScanMode.RAW_BLOCK,
                    bytesScanned = bytesScanned,
                    totalBytesEstimate = total,
                    badRegions = badRegions.toList(),
                    currentTarget = device.path,
                    message = msg,
                    health = healthFor(badRegions, readErrors),
                    readErrors = readErrors,
                    verifiedBytes = bytesScanned - (readErrors * chunkSize).coerceAtLeast(0L),
                    speedBytesPerSec = bytesScanned * 1000L / elapsed
                )
            )
        }

        try {
            emit(ScanState.SCANNING, "Starting raw scan of ${device.name} (${device.path})")
            var offset = 0L
            while (offset < total) {
                coroutineContext.ensureActive()
                val count = minOf(chunkSize, total - offset)
                val skipBlocks = offset / 4096L
                val countBlocks = (count + 4095L) / 4096L
                // Read chunk via dd; discard output, check exit code. Use 4096-byte sectors for alignment.
                val cmd = "dd if=${device.path} bs=4096 skip=$skipBlocks count=$countBlocks of=/dev/null 2>&1; echo __EXIT:$?"
                val r = runSu(cmd, timeoutMs = 60_000)
                val exitOk = r.stdout.contains("__EXIT:0")
                bytesScanned += count
                if (!exitOk) {
                    readErrors++
                    badRegions.add(
                        BadRegion(
                            pathOrDevice = device.path,
                            offsetBytes = offset,
                            lengthBytes = count,
                            description = "Raw read error at offset $offset (${count} bytes): ${r.stdout.lineSequence().firstOrNull { !it.startsWith("__EXIT:") } ?: "I/O error"}"
                        )
                    )
                }
                if (offset % (32L * chunkSize) == 0L || !exitOk) {
                    emit(ScanState.SCANNING, "Scanned ${formatBytes(bytesScanned)} / ${formatBytes(total)}")
                }
                offset += count
            }
            val msg = "Raw scan complete. ${badRegions.size} bad region(s) found."
            emit(ScanState.COMPLETED, msg)
            ScanProgress(
                state = ScanState.COMPLETED,
                mode = ScanMode.RAW_BLOCK,
                bytesScanned = bytesScanned,
                totalBytesEstimate = total,
                badRegions = badRegions.toList(),
                currentTarget = device.path,
                message = msg,
                health = healthFor(badRegions, readErrors),
                readErrors = readErrors,
                verifiedBytes = bytesScanned,
                speedBytesPerSec = bytesScanned * 1000L / max(1L, System.currentTimeMillis() - start)
            )
        } catch (ce: kotlinx.coroutines.CancellationException) {
            val msg = "Raw scan cancelled."
            emit(ScanState.CANCELLED, msg)
            ScanProgress(
                state = ScanState.CANCELLED, mode = ScanMode.RAW_BLOCK, bytesScanned = bytesScanned,
                totalBytesEstimate = total, badRegions = badRegions.toList(), currentTarget = device.path,
                message = msg, health = healthFor(badRegions, readErrors), readErrors = readErrors
            )
        } catch (t: Throwable) {
            val msg = "Raw scan failed: ${t.message}"
            emit(ScanState.FAILED, msg)
            ScanProgress(
                state = ScanState.FAILED, mode = ScanMode.RAW_BLOCK, bytesScanned = bytesScanned,
                totalBytesEstimate = total, badRegions = badRegions.toList(), currentTarget = device.path,
                message = msg, health = HealthStatus.CRITICAL, readErrors = readErrors
            )
        }
    }

    data class CmdResult(val exitCode: Int, val stdout: String)

    fun runSu(command: String, timeoutMs: Long = 15_000): CmdResult {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val stdout = StringBuilder()
            val err = StringBuilder()
            val tOut = Thread { try { p.inputStream.bufferedReader().use { stdout.append(it.readText()) } } catch (_: Throwable) {} }
            val tErr = Thread { try { p.errorStream.bufferedReader().use { err.append(it.readText()) } } catch (_: Throwable) {} }
            tOut.start(); tErr.start()
            val worker = Thread { try { p.waitFor() } catch (_: Throwable) {} }
            worker.start()
            worker.join(timeoutMs)
            if (worker.isAlive) {
                try { p.destroy() } catch (_: Throwable) {}
                try { p.destroyForcibly() } catch (_: Throwable) {}
                worker.join(1000)
                tOut.join(1000); tErr.join(1000)
                return CmdResult(-1, stdout.toString() + "\n" + err.toString() + "\nTIMEOUT")
            }
            tOut.join(1000); tErr.join(1000)
            val exit = try { p.exitValue() } catch (_: Throwable) { -1 }
            val combined = stdout.toString().ifBlank { err.toString() }
            CmdResult(exit, combined)
        } catch (t: Throwable) {
            CmdResult(-1, t.message ?: "error")
        }
    }

    private fun healthFor(bad: List<BadRegion>, readErrors: Long): HealthStatus = when {
        bad.isEmpty() && readErrors == 0L -> HealthStatus.HEALTHY
        bad.size < 5 -> HealthStatus.DEGRADED
        else -> HealthStatus.CRITICAL
    }

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return "%.1f KB".format(kb)
            val mb = kb / 1024.0
            if (mb < 1024) return "%.1f MB".format(mb)
            val gb = mb / 1024.0
            return "%.2f GB".format(gb)
        }
    }
}
