package com.buboadonis.s7health.model

enum class ScanMode { FILE_LEVEL, RAW_BLOCK }

enum class ScanState { IDLE, SCANNING, REPAIRING, COMPLETED, FAILED, CANCELLED }

enum class HealthStatus { UNKNOWN, HEALTHY, DEGRADED, CRITICAL }

data class BadRegion(
    val pathOrDevice: String,
    val offsetBytes: Long,
    val lengthBytes: Long,
    val description: String,
    val repaired: Boolean = false,
    val markedUnusable: Boolean = false
)

data class ScanProgress(
    val state: ScanState = ScanState.IDLE,
    val mode: ScanMode = ScanMode.FILE_LEVEL,
    val bytesScanned: Long = 0L,
    val totalBytesEstimate: Long = 0L,
    val badRegions: List<BadRegion> = emptyList(),
    val currentTarget: String = "",
    val message: String = "Ready.",
    val health: HealthStatus = HealthStatus.UNKNOWN,
    val readErrors: Long = 0L,
    val writeErrors: Long = 0L,
    val verifiedBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L
) {
    val progressPercent: Int
        get() = if (totalBytesEstimate > 0L) {
            ((bytesScanned.coerceAtMost(totalBytesEstimate) * 100) / totalBytesEstimate).toInt()
        } else 0
}
