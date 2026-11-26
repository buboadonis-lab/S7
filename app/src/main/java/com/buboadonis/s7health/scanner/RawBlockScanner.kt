package com.buboadonis.s7health.scanner

import com.buboadonis.s7health.model.BadRegion
import com.buboadonis.s7health.model.HealthStatus
import com.buboadonis.s7health.model.ScanMode
import com.buboadonis.s7health.model.ScanProgress
import com.buboadonis.s7health.model.ScanState
import com.buboadonis.s7health.util.ScanCheckpoint
import com.buboadonis.s7health.util.SectorMapStore
import com.buboadonis.s7health.util.SectorStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.max

class RawBlockScanner {

    data class BlockDevice(val path: String, val name: String, val sizeBytes: Long)

    fun isRootAvailable(): Boolean {
        return try {
            val r = runSu("id", timeoutMs = 10_000)
            r.exitCode == 0 && r.stdout.contains("uid=0")
        } catch (_: Throwable) {
            false
        }
    }

    fun listBlockDevices(): List<BlockDevice> {
        val devices = mutableListOf<BlockDevice>()
        try {
            val byName = runSu("ls -1 /dev/block/by-name 2>/dev/null")
            if (byName.exitCode == 0) {
                byName.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { name ->
                    val realPath = runSu("readlink -f /dev/block/by-name/$name").stdout.trim()
                        .ifEmpty { "/dev/block/by-name/$name" }
                    val size = blockSizeBytes(realPath)
                    devices.add(BlockDevice(realPath, name, size))
                }
            }
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
        val name = File(path).name
        val sys = runSu("cat /sys/class/block/$name/size 2>/dev/null")
        val sectors = sys.stdout.trim().toLongOrNull() ?: return 0L
        return sectors * 512L
    }

    suspend fun scanBlockDevice(
        device: BlockDevice,
        maxBytes: Long = device.sizeBytes.takeIf { it > 0 } ?: (256L * 1024 * 1024),
        resume: ScanCheckpoint? = null,
        sectorMap: com.buboadonis.s7health.util.SectorMapStore? = null,
        onCheckpoint: (ScanCheckpoint) -> Unit = {},
        onProgress: (ScanProgress) -> Unit
    ): ScanProgress = withContext(Dispatchers.IO) {
        val badRegions = mutableListOf<BadRegion>().apply { resume?.badRegions?.let { addAll(it) } }
        var bytesScanned = resume?.bytesScanned ?: 0L
        var readErrors = resume?.readErrors ?: 0L
        val start = System.currentTimeMillis()
        val chunkSize = 512 * 1024L // 512KB chunks - smaller for safer resume on bad sectors
        val total = if (device.sizeBytes > 0) minOf(device.sizeBytes, maxBytes) else maxBytes
        var offset = (resume?.rawOffsetBytes ?: 0L).coerceIn(0L, total)

        // If resuming, checkpoint was saved BEFORE reading offset; skip that chunk to avoid a reboot loop.
        if (resume != null && offset < total) {
            val skipCount = minOf(chunkSize, total - offset)
            badRegions.add(BadRegion(device.path, offset, skipCount, "Skipped on resume: suspected crash/hang/reboot while reading this block - marked PERMANENTLY UNUSABLE"))
            readErrors++
            bytesScanned += skipCount
            sectorMap?.mark(device.path, offset, skipCount, SectorStatus.BAD_PERMANENT)
            sectorMap?.save()
            offset += skipCount
        }

        fun saveCheckpoint() {
            onCheckpoint(
                ScanCheckpoint(
                    mode = ScanMode.RAW_BLOCK,
                    target = device.path,
                    rawOffsetBytes = offset,
                    rawTotalBytes = total,
                    bytesScanned = bytesScanned,
                    readErrors = readErrors,
                    badRegions = badRegions.toList()
                )
            )
        }

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
                    speedBytesPerSec = (bytesScanned - (resume?.bytesScanned ?: 0L)).coerceAtLeast(0L) * 1000L / elapsed
                )
            )
        }

        try {
            emit(ScanState.SCANNING, if (resume != null) "Resuming raw scan at offset $offset..." else "Starting raw scan of ${device.name} (${device.path})")
            while (offset < total) {
                coroutineContext.ensureActive()
                val count = minOf(chunkSize, total - offset)

                // Never rescan sectors marked permanently bad (policy: crash/power-off during scan => permanently unusable)
                if (sectorMap?.isBadPermanent(device.path, offset, count) == true) {
                    bytesScanned += count
                    offset += count
                    if (offset % (16L * chunkSize) == 0L) {
                        saveCheckpoint()
                        emit(ScanState.SCANNING, "Skipping permanently bad region, scanned ${formatBytes(bytesScanned)} / ${formatBytes(total)}")
                    }
                    continue
                }

                // Checkpoint BEFORE reading so a device reboot on a bad sector allows resume at next chunk
                saveCheckpoint()

                val skipBlocks = offset / 4096L
                val countBlocks = (count + 4095L) / 4096L
                val cmd = "dd if=${device.path} bs=4096 skip=$skipBlocks count=$countBlocks of=/dev/null 2>&1; echo __EXIT:$?"
                val r = runSu(cmd, timeoutMs = 30_000)
                val exitOk = r.stdout.contains("__EXIT:0")
                bytesScanned += count
                if (!exitOk) {
                    readErrors++
                    badRegions.add(
                        BadRegion(
                            pathOrDevice = device.path,
                            offsetBytes = offset,
                            lengthBytes = count,
                            description = "Raw read error at offset $offset (${count} bytes): ${r.stdout.lineSequence().firstOrNull { !it.startsWith("__EXIT:") && it.isNotBlank() } ?: "I/O error"}"
                        )
                    )
                    sectorMap?.mark(device.path, offset, count, SectorStatus.BAD_PERMANENT)
                    sectorMap?.save()
                } else {
                    sectorMap?.mark(device.path, offset, count, SectorStatus.HEALTHY)
                }
                offset += count
                if (offset % (16L * chunkSize) == 0L || !exitOk) {
                    sectorMap?.save()
                    saveCheckpoint()
                    emit(ScanState.SCANNING, "Scanned ${formatBytes(bytesScanned)} / ${formatBytes(total)}" + if (readErrors > 0) " ($readErrors errors)" else "")
                }
            }
            sectorMap?.save()
            saveCheckpoint()
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
                speedBytesPerSec = (bytesScanned - (resume?.bytesScanned ?: 0L)).coerceAtLeast(0L) * 1000L / max(1L, System.currentTimeMillis() - start)
            )
        } catch (ce: kotlinx.coroutines.CancellationException) {
            saveCheckpoint()
            val msg = "Raw scan cancelled. Progress saved for resume."
            emit(ScanState.CANCELLED, msg)
            ScanProgress(
                state = ScanState.CANCELLED, mode = ScanMode.RAW_BLOCK, bytesScanned = bytesScanned,
                totalBytesEstimate = total, badRegions = badRegions.toList(), currentTarget = device.path,
                message = msg, health = healthFor(badRegions, readErrors), readErrors = readErrors
            )
        } catch (t: Throwable) {
            saveCheckpoint()
            val msg = "Raw scan failed: ${t.message}. Progress saved for resume."
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
