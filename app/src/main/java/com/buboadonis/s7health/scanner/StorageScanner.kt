package com.buboadonis.s7health.scanner

import com.buboadonis.s7health.model.BadRegion
import com.buboadonis.s7health.model.HealthStatus
import com.buboadonis.s7health.model.ScanMode
import com.buboadonis.s7health.model.ScanProgress
import com.buboadonis.s7health.model.ScanState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.random.Random

class StorageScanner {

    private val bufferSize = 64 * 1024
    private val patternBlockSize = 4096

    suspend fun scanAccessible(
        roots: List<File>,
        patternTestMb: Int = 16,
        onProgress: (ScanProgress) -> Unit
    ): ScanProgress = withContext(Dispatchers.IO) {
        val badRegions = mutableListOf<BadRegion>()
        var bytesScanned = 0L
        var readErrors = 0L
        var writeErrors = 0L
        var verifiedBytes = 0L
        val start = System.currentTimeMillis()

        val files = roots.flatMap { collectReadableFiles(it) }
        val totalEstimate = files.sumOf { it.length().coerceAtLeast(0L) } +
            (patternTestMb.toLong() * 1024L * 1024L * roots.size)

        fun emit(state: ScanState, target: String, msg: String) {
            val elapsed = max(1L, System.currentTimeMillis() - start)
            val speed = bytesScanned * 1000L / elapsed
            onProgress(
                ScanProgress(
                    state = state,
                    mode = ScanMode.FILE_LEVEL,
                    bytesScanned = bytesScanned,
                    totalBytesEstimate = totalEstimate,
                    badRegions = badRegions.toList(),
                    currentTarget = target,
                    message = msg,
                    health = computeHealth(badRegions, readErrors, writeErrors),
                    readErrors = readErrors,
                    writeErrors = writeErrors,
                    verifiedBytes = verifiedBytes,
                    speedBytesPerSec = speed
                )
            )
        }

        try {
            emit(ScanState.SCANNING, "", "Enumerating accessible files...")

            for (file in files) {
                coroutineContext.ensureActive()
                emit(ScanState.SCANNING, file.absolutePath, "Reading: ${file.name}")
                val result = scanFileRead(file)
                bytesScanned += result.bytesRead
                if (result.error != null) {
                    readErrors++
                    badRegions.add(
                        BadRegion(
                            pathOrDevice = file.absolutePath,
                            offsetBytes = result.errorOffset,
                            lengthBytes = -1L,
                            description = "Read error: ${result.error}"
                        )
                    )
                }
            }

            for (root in roots) {
                coroutineContext.ensureActive()
                val testDir = resolveWritableDir(root) ?: continue
                emit(ScanState.SCANNING, testDir.absolutePath, "Bit-pattern test in ${testDir.absolutePath}")
                val patternResult = runPatternTest(testDir, patternTestMb)
                bytesScanned += patternResult.bytesScanned
                verifiedBytes += patternResult.verifiedBytes
                readErrors += patternResult.readErrors
                writeErrors += patternResult.writeErrors
                badRegions.addAll(patternResult.badRegions)
            }

            val finalState = ScanState.COMPLETED
            val health = computeHealth(badRegions, readErrors, writeErrors)
            val msg = "Scan complete. ${badRegions.size} issue(s) found."
            emit(finalState, "", msg)
            ScanProgress(
                state = finalState,
                mode = ScanMode.FILE_LEVEL,
                bytesScanned = bytesScanned,
                totalBytesEstimate = totalEstimate,
                badRegions = badRegions.toList(),
                currentTarget = "",
                message = msg,
                health = health,
                readErrors = readErrors,
                writeErrors = writeErrors,
                verifiedBytes = verifiedBytes,
                speedBytesPerSec = bytesScanned * 1000L / max(1L, System.currentTimeMillis() - start)
            )
        } catch (ce: kotlinx.coroutines.CancellationException) {
            val msg = "Scan cancelled."
            emit(ScanState.CANCELLED, "", msg)
            ScanProgress(
                state = ScanState.CANCELLED,
                mode = ScanMode.FILE_LEVEL,
                bytesScanned = bytesScanned,
                totalBytesEstimate = totalEstimate,
                badRegions = badRegions.toList(),
                message = msg,
                health = computeHealth(badRegions, readErrors, writeErrors),
                readErrors = readErrors,
                writeErrors = writeErrors,
                verifiedBytes = verifiedBytes
            )
        } catch (t: Throwable) {
            val msg = "Scan failed: ${t.message}"
            emit(ScanState.FAILED, "", msg)
            ScanProgress(
                state = ScanState.FAILED,
                mode = ScanMode.FILE_LEVEL,
                bytesScanned = bytesScanned,
                totalBytesEstimate = totalEstimate,
                badRegions = badRegions.toList(),
                message = msg,
                health = HealthStatus.CRITICAL,
                readErrors = readErrors,
                writeErrors = writeErrors,
                verifiedBytes = verifiedBytes
            )
        }
    }

    private data class FileReadResult(val bytesRead: Long, val errorOffset: Long = 0L, val error: String? = null)

    private fun scanFileRead(file: File): FileReadResult {
        var bytesRead = 0L
        val buf = ByteArray(bufferSize)
        return try {
            file.inputStream().buffered(bufferSize).use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    bytesRead += n
                }
            }
            FileReadResult(bytesRead)
        } catch (t: Throwable) {
            FileReadResult(bytesRead, bytesRead, t.message ?: t.javaClass.simpleName)
        }
    }

    private data class PatternResult(
        val bytesScanned: Long,
        val verifiedBytes: Long,
        val readErrors: Long,
        val writeErrors: Long,
        val badRegions: List<BadRegion>
    )

    private suspend fun runPatternTest(dir: File, sizeMb: Int): PatternResult {
        val testFile = File(dir, ".s7health_pattern_test.tmp")
        val bad = mutableListOf<BadRegion>()
        var bytesScanned = 0L
        var verified = 0L
        var readErrors = 0L
        var writeErrors = 0L
        val totalBytes = sizeMb.toLong() * 1024L * 1024L
        val patterns = listOf(
            ByteArray(patternBlockSize) { 0x00 },
            ByteArray(patternBlockSize) { 0xFF.toByte() },
            ByteArray(patternBlockSize) { if (it % 2 == 0) 0xAA.toByte() else 0x55.toByte() },
            ByteArray(patternBlockSize) { if (it % 2 == 0) 0x55.toByte() else 0xAA.toByte() },
            Random(0x57EED).nextBytes(ByteArray(patternBlockSize))
        )

        try {
            if (testFile.exists()) testFile.delete()
            RandomAccessFile(testFile, "rw").use { raf ->
                raf.setLength(totalBytes)
                var offset = 0L
                var patternIdx = 0
                while (offset < totalBytes) {
                    coroutineContext.ensureActive()
                    val block = patterns[patternIdx % patterns.size]
                    val toWrite = minOf(block.size.toLong(), totalBytes - offset).toInt()
                    try {
                        raf.seek(offset)
                        raf.write(block, 0, toWrite)
                    } catch (t: Throwable) {
                        writeErrors++
                        bad.add(BadRegion(testFile.absolutePath, offset, toWrite.toLong(), "Write error: ${t.message}"))
                    }
                    offset += toWrite
                    bytesScanned += toWrite
                    patternIdx++
                }
                raf.fd.sync()

                // Read back and verify bit-by-bit
                offset = 0L
                patternIdx = 0
                val readBuf = ByteArray(patternBlockSize)
                while (offset < totalBytes) {
                    coroutineContext.ensureActive()
                    val expected = patterns[patternIdx % patterns.size]
                    val toRead = minOf(expected.size.toLong(), totalBytes - offset).toInt()
                    try {
                        raf.seek(offset)
                        var readTotal = 0
                        while (readTotal < toRead) {
                            val n = raf.read(readBuf, readTotal, toRead - readTotal)
                            if (n < 0) break
                            readTotal += n
                        }
                        bytesScanned += readTotal
                        // Bit-by-bit comparison
                        var mismatchStart = -1
                        var blockHasMismatch = false
                        for (i in 0 until readTotal) {
                            val ok = readBuf[i] == expected[i]
                            if (!ok && mismatchStart < 0) mismatchStart = i
                            if (ok && mismatchStart >= 0) {
                                blockHasMismatch = true
                                bad.add(BadRegion(testFile.absolutePath, offset + mismatchStart, (i - mismatchStart).toLong(),
                                    "Bit mismatch: expected 0x%02X got 0x%02X".format(expected[mismatchStart].toInt() and 0xFF, readBuf[mismatchStart].toInt() and 0xFF)))
                                mismatchStart = -1
                            }
                        }
                        if (mismatchStart >= 0) {
                            blockHasMismatch = true
                            bad.add(BadRegion(testFile.absolutePath, offset + mismatchStart, (readTotal - mismatchStart).toLong(),
                                "Bit mismatch: expected 0x%02X got 0x%02X".format(expected[mismatchStart].toInt() and 0xFF, readBuf[mismatchStart].toInt() and 0xFF)))
                        }
                        if (readTotal == toRead && !blockHasMismatch) {
                            verified += toRead
                        }
                    } catch (t: Throwable) {
                        readErrors++
                        bad.add(BadRegion(testFile.absolutePath, offset, toRead.toLong(), "Read-back error: ${t.message}"))
                    }
                    offset += toRead
                    patternIdx++
                }
            }
        } catch (t: Throwable) {
            writeErrors++
            bad.add(BadRegion(testFile.absolutePath, 0L, totalBytes, "Pattern test failed: ${t.message}"))
        } finally {
            try { if (testFile.exists()) testFile.delete() } catch (_: Throwable) {}
        }
        return PatternResult(bytesScanned, verified, readErrors, writeErrors, bad)
    }

    private fun collectReadableFiles(root: File, limit: Int = 5000): List<File> {
        val out = mutableListOf<File>()
        val stack = ArrayDeque<File>()
        if (root.exists()) stack.add(root)
        while (stack.isNotEmpty() && out.size < limit) {
            val f = stack.removeLast()
            try {
                if (f.isFile && f.canRead()) out.add(f)
                else if (f.isDirectory && f.canRead()) {
                    f.listFiles()?.forEach { child ->
                        val name = child.name
                        if (name == "." || name == "..") return@forEach
                        // Skip the test file and common virtual dirs
                        if (name.startsWith(".s7health_")) return@forEach
                        stack.add(child)
                    }
                }
            } catch (_: Throwable) { }
        }
        return out
    }

    private fun resolveWritableDir(root: File): File? {
        val candidates = listOf(root, File(root, "Download"), File(root, "Documents"))
        for (c in candidates) {
            try {
                if (!c.exists()) c.mkdirs()
                if (c.canWrite()) return c
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun computeHealth(bad: List<BadRegion>, readErrors: Long, writeErrors: Long): HealthStatus {
        return when {
            bad.isEmpty() && readErrors == 0L && writeErrors == 0L -> HealthStatus.HEALTHY
            bad.size < 5 && readErrors < 5 && writeErrors == 0L -> HealthStatus.DEGRADED
            else -> HealthStatus.CRITICAL
        }
    }
}
