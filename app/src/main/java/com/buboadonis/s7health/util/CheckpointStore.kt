package com.buboadonis.s7health.util

import android.content.Context
import com.buboadonis.s7health.model.BadRegion
import com.buboadonis.s7health.model.ScanMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ScanCheckpoint(
    val mode: ScanMode,
    val target: String,
    val roots: List<String> = emptyList(),
    val fileIndex: Int = 0,
    val rawOffsetBytes: Long = 0L,
    val rawTotalBytes: Long = 0L,
    val bytesScanned: Long = 0L,
    val verifiedBytes: Long = 0L,
    val readErrors: Long = 0L,
    val writeErrors: Long = 0L,
    val badRegions: List<BadRegion> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

class CheckpointStore(context: Context) {
    private val file = File(context.filesDir, "scan_checkpoint.json")

    fun save(cp: ScanCheckpoint) {
        try {
            val json = JSONObject()
            json.put("mode", cp.mode.name)
            json.put("target", cp.target)
            json.put("roots", JSONArray(cp.roots))
            json.put("fileIndex", cp.fileIndex)
            json.put("rawOffsetBytes", cp.rawOffsetBytes)
            json.put("rawTotalBytes", cp.rawTotalBytes)
            json.put("bytesScanned", cp.bytesScanned)
            json.put("verifiedBytes", cp.verifiedBytes)
            json.put("readErrors", cp.readErrors)
            json.put("writeErrors", cp.writeErrors)
            json.put("timestamp", cp.timestamp)
            val badArr = JSONArray()
            cp.badRegions.forEach { b ->
                val o = JSONObject()
                o.put("pathOrDevice", b.pathOrDevice)
                o.put("offsetBytes", b.offsetBytes)
                o.put("lengthBytes", b.lengthBytes)
                o.put("description", b.description)
                o.put("repaired", b.repaired)
                o.put("markedUnusable", b.markedUnusable)
                badArr.put(o)
            }
            json.put("badRegions", badArr)
            file.writeText(json.toString())
        } catch (_: Throwable) { }
    }

    fun load(): ScanCheckpoint? {
        return try {
            if (!file.exists()) return null
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
            val rootsArr = json.optJSONArray("roots") ?: JSONArray()
            val roots = mutableListOf<String>()
            for (i in 0 until rootsArr.length()) roots.add(rootsArr.optString(i))
            ScanCheckpoint(
                mode = ScanMode.valueOf(json.optString("mode", ScanMode.FILE_LEVEL.name)),
                target = json.optString("target"),
                roots = roots,
                fileIndex = json.optInt("fileIndex"),
                rawOffsetBytes = json.optLong("rawOffsetBytes"),
                rawTotalBytes = json.optLong("rawTotalBytes"),
                bytesScanned = json.optLong("bytesScanned"),
                verifiedBytes = json.optLong("verifiedBytes"),
                readErrors = json.optLong("readErrors"),
                writeErrors = json.optLong("writeErrors"),
                badRegions = bad,
                timestamp = json.optLong("timestamp")
            )
        } catch (_: Throwable) { null }
    }

    fun clear() { try { if (file.exists()) file.delete() } catch (_: Throwable) {} }
    fun exists(): Boolean = file.exists()
}
