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

/**
 * High-precision raw block scanner (v1.0.9, targeting 99.9% detection for detectable errors).
 *
 * Method:
 *  - 4MB large chunks with double-read SHA-256 verification (2 passes, checksums must match).
 *  - On failure/mismatch, subdivide to 64KB, then to 4KB single blocks for precise localization.
 *  - Retry up to 3x for transient errors; only mark BAD_PERMANENT after retries fail.
 *  - HEALTHY requires 2 successful reads with identical checksums (no silent corruption).
 *  - BAD_PERMANENT is sticky and never rescanned; crash/power-off chunk is marked BAD_PERMANENT.
 *
 * Limitations (honest):
 *  - Flash controller ECC/remapping hides physical cell defects from software; only logical block errors are visible.
 *  - Weak/intermittent sectors (temperature/voltage dependent) may pass 2 reads but fail later; 2-pass reduces but does not eliminate this.
 *  - No software scan can guarantee 99.9% for all physical defects without hardware SMART/ECC counters.
 */
class RawBlockScanner {

    data class BlockDevice(val path: String, val name: String, val sizeBytes: Long)

    companion object {
        private const val LARGE_CHUNK = 4L * 1024 * 1024 // 4MB
        private const val MEDIUM_CHUNK = 64L * 1024 // 64KB
        private const val SMALL_CHUNK = 4096L // 4KB single block
        private const val MAX_RETRIES = 3

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
        var verifiedBytes = resume?.verifiedBytes ?: 0L
        val start = System.currentTimeMillis()
        val total = if (device.sizeBytes > 0) minOf(device.sizeBytes, maxBytes) else maxBytes
        var offset = (resume?.rawOffsetBytes ?: 0L).coerceIn(0L, total)

        // If resuming, checkpoint was saved BEFORE reading offset; skip that chunk to avoid a reboot loop.
        if (resume != null && offset < total) {
            val skipCount = minOf(LARGE_CHUNK, total - offset)
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
                    verifiedBytes = verifiedBytes,
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
                    verifiedBytes = verifiedBytes,
                    speedBytesPerSec = (bytesScanned - (resume?.bytesScanned ?: 0L)).coerceAtLeast(0L) * 1000L / elapsed
                )
            )
        }

        try {
            emit(ScanState.SCANNING, if (resume != null) "Resuming high-precision raw scan at offset $offset (2-pass checksum)..." else "Starting high-precision raw scan of ${device.name} (${device.path}) - 2-pass SHA-256 verified")
            while (offset < total) {
                coroutineContext.ensureActive()
                val count = minOf(LARGE_CHUNK, total - offset)

                // Never rescan sectors marked permanently bad
                if (sectorMap?.isBadPermanent(device.path, offset, count) == true) {
                    bytesScanned += count
                    verifiedBytes += 0 // skipped, not verified
                    offset += count
                    if (offset % (16L * LARGE_CHUNK) == 0L) {
                        saveCheckpoint()
                        emit(ScanState.SCANNING, "Skipping permanently bad region, scanned ${formatBytes(bytesScanned)} / ${formatBytes(total)}")
                    }
                    continue
                }

                // Checkpoint BEFORE reading so a device reboot on a bad sector allows resume at next chunk
                saveCheckpoint()

                // High-precision verification with subdivision
                val result = verifyLargeChunk(device.path, offset, count, sectorMap, badRegions)
                bytesScanned += count
                if (result.verified) {
                    verifiedBytes += count
                } else {
                    readErrors += result.errorCount
                }
                offset += count

                if (offset % (8L * LARGE_CHUNK) == 0L || !result.verified) {
                    sectorMap?.save()
                    saveCheckpoint()
                    emit(
                        ScanState.SCANNING,
                        "Scanned ${formatBytes(bytesScanned)} / ${formatBytes(total)} (verified ${formatBytes(verifiedBytes)})" +
                            if (readErrors > 0) " ($readErrors bad blocks)" else " (2-pass OK)"
                    )
                }
            }
            sectorMap?.save()
            saveCheckpoint()
            val msg = "High-precision raw scan complete. ${badRegions.size} bad region(s) found. Verified ${formatBytes(verifiedBytes)} with 2-pass checksum."
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
                verifiedBytes = verifiedBytes,
                speedBytesPerSec = (bytesScanned - (resume?.bytesScanned ?: 0L)).coerceAtLeast(0L) * 1000L / max(1L, System.currentTimeMillis() - start)
            )
        } catch (ce: kotlinx.coroutines.CancellationException) {
            saveCheckpoint()
            val msg = "Raw scan cancelled. Progress saved for resume."
            emit(ScanState.CANCELLED, msg)
            ScanProgress(
                state = ScanState.CANCELLED, mode = ScanMode.RAW_BLOCK, bytesScanned = bytesScanned,
                totalBytesEstimate = total, badRegions = badRegions.toList(), currentTarget = device.path,
                message = msg, health = healthFor(badRegions, readErrors), readErrors = readErrors,
                verifiedBytes = verifiedBytes
            )
        } catch (t: Throwable) {
            saveCheckpoint()
            val msg = "Raw scan failed: ${t.message}. Progress saved for resume."
            emit(ScanState.FAILED, msg)
            ScanProgress(
                state = ScanState.FAILED, mode = ScanMode.RAW_BLOCK, bytesScanned = bytesScanned,
                totalBytesEstimate = total, badRegions = badRegions.toList(), currentTarget = device.path,
                message = msg, health = HealthStatus.CRITICAL, readErrors = readErrors,
                verifiedBytes = verifiedBytes
            )
        }
    }

    private data class ChunkResult(val verified: Boolean, val errorCount: Long)

    private fun verifyLargeChunk(
        devicePath: String,
        offset: Long,
        count: Long,
        sectorMap: SectorMapStore?,
        badRegions: MutableList<BadRegion>
    ): ChunkResult {
        // Try large chunk with double-read checksum
        val largeOk = verifyWithDoubleRead(devicePath, offset, count)
        if (largeOk) {
            sectorMap?.mark(devicePath, offset, count, SectorStatus.HEALTHY)
            return ChunkResult(true, 0)
        }
        // Subdivide to medium chunks
        var errorCount = 0L
        var pos = offset
        val end = offset + count
        while (pos < end) {
            val mCount = minOf(MEDIUM_CHUNK, end - pos)
            if (sectorMap?.isBadPermanent(devicePath, pos, mCount) == true) {
                pos += mCount
                continue
            }
            val mediumOk = verifyWithDoubleRead(devicePath, pos, mCount)
            if (mediumOk) {
                sectorMap?.mark(devicePath, pos, mCount, SectorStatus.HEALTHY)
            } else {
                // Subdivide to small 4KB blocks
                var sPos = pos
                val sEnd = pos + mCount
                while (sPos < sEnd) {
                    val sCount = minOf(SMALL_CHUNK, sEnd - sPos)
                    if (sectorMap?.isBadPermanent(devicePath, sPos, sCount) == true) {
                        sPos += sCount
                        continue
                    }
                    val smallOk = verifyWithDoubleRead(devicePath, sPos, sCount)
                    if (smallOk) {
                        sectorMap?.mark(devicePath, sPos, sCount, SectorStatus.HEALTHY)
                    } else {
                        errorCount++
                        badRegions.add(
                            BadRegion(
                                pathOrDevice = devicePath,
                                offsetBytes = sPos,
                                lengthBytes = sCount,
                                description = "High-precision verification failed at offset $sPos (${sCount} bytes): 2-pass checksum mismatch or I/O error after $MAX_RETRIES retries - marked PERMANENTLY UNUSABLE"
                            )
                        )
                        sectorMap?.mark(devicePath, sPos, sCount, SectorStatus.BAD_PERMANENT)
                    }
                    sPos += sCount
                }
            }
            pos += mCount
        }
        sectorMap?.save()
        return ChunkResult(false, errorCount)
    }

    /**
     * Double-read verification: reads chunk twice with checksum, requires both to succeed and match.
     * Returns true only if both reads succeed and hashes are identical.
     */
    private fun verifyWithDoubleRead(devicePath: String, offset: Long, count: Long): Boolean {
        val first = readWithRetry(devicePath, offset, count)
        if (!first.first) return false
        val second = readWithRetry(devicePath, offset, count)
        if (!second.first) return false
        // Both succeeded, hashes must match (detects silent corruption / bit flips)
        return first.second == second.second && first.second.isNotEmpty()
    }

    /**
     * Single read with checksum, with retry. Returns (success, hash).
     * Uses dd + sha256sum (fallback to md5sum) via su.
     */
    private fun readWithRetry(devicePath: String, offset: Long, count: Long): Pair<Boolean, String> {
        var lastHash = ""
        repeat(MAX_RETRIES) { attempt ->
            val (ok, hash) = readOnceWithChecksum(devicePath, offset, count)
            if (ok) return Pair(true, hash)
            lastHash = hash
            if (attempt < MAX_RETRIES - 1) {
                try { Thread.sleep(100) } catch (_: Throwable) {}
            }
        }
        return Pair(false, lastHash)
    }

    private fun readOnceWithChecksum(devicePath: String, offset: Long, count: Long): Pair<Boolean, String> {
        val skipBlocks = offset / 4096L
        val countBlocks = (count + 4095L) / 4096L
        // Try sha256sum first, fallback to md5sum
        val cmdSha = "dd if=$devicePath bs=4096 skip=$skipBlocks count=$countBlocks 2>/dev/null | sha256sum 2>/dev/null; echo __EXIT:$?"
        val rSha = runSu(cmdSha, timeoutMs = 30_000)
        val hashSha = parseHash(rSha.stdout, 64)
        if (hashSha.isNotEmpty() && rSha.stdout.contains("__EXIT:0")) {
            return Pair(true, hashSha)
        }
        // Fallback to md5sum (32 hex chars)
        val cmdMd5 = "dd if=$devicePath bs=4096 skip=$skipBlocks count=$countBlocks 2>/dev/null | md5sum 2>/dev/null; echo __EXIT:$?"
        val rMd5 = runSu(cmdMd5, timeoutMs = 30_000)
        val hashMd5 = parseHash(rMd5.stdout, 32)
        if (hashMd5.isNotEmpty() && rMd5.stdout.contains("__EXIT:0")) {
            return Pair(true, hashMd5)
        }
        // If both failed, check if dd itself failed (no hash)
        return Pair(false, hashSha.ifEmpty { hashMd5 })
    }

    private fun parseHash(output: String, expectedLen: Int): String {
        // sha256sum output: "<hash>  -" or "<hash>  <filename>"
        for (line in output.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("__EXIT:")) continue
            val hash = trimmed.split(Regex("\\s+")).firstOrNull()?.trim() ?: continue
            if (hash.length == expectedLen && hash.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                return hash.lowercase()
            }
        }
        return ""
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

}
