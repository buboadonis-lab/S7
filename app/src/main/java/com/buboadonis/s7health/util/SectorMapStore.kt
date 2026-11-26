package com.buboadonis.s7health.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class SectorStatus { UNVERIFIED, HEALTHY, BAD_PERMANENT }

data class SectorRange(val offset: Long, val length: Long, val status: SectorStatus)

class SectorMapStore(context: Context) {
    private val file = File(context.filesDir, "sector_map.json")
    private val lock = Any()
    // target -> list of ranges (non-overlapping preferred, but we keep simple)
    private val data: MutableMap<String, MutableList<SectorRange>> = mutableMapOf()

    init { load() }

    @Synchronized
    private fun load() {
        try {
            if (!file.exists()) return
            val root = JSONObject(file.readText())
            val targets = root.optJSONObject("targets") ?: return
            for (key in targets.keys()) {
                val arr = targets.getJSONArray(key)
                val list = mutableListOf<SectorRange>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        SectorRange(
                            offset = o.optLong("offset"),
                            length = o.optLong("length"),
                            status = SectorStatus.valueOf(o.optString("status", "UNVERIFIED"))
                        )
                    )
                }
                data[key] = list
            }
        } catch (_: Throwable) { data.clear() }
    }

    @Synchronized
    fun save() {
        try {
            val root = JSONObject()
            val targets = JSONObject()
            data.forEach { (k, v) ->
                val arr = JSONArray()
                v.forEach { r ->
                    val o = JSONObject()
                    o.put("offset", r.offset)
                    o.put("length", r.length)
                    o.put("status", r.status.name)
                    arr.put(o)
                }
                targets.put(k, arr)
            }
            root.put("targets", targets)
            val tmp = File(file.parentFile, file.name + ".tmp")
            java.io.FileOutputStream(tmp).use { fos ->
                fos.write(root.toString().toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString())
                tmp.delete()
            }
        } catch (_: Throwable) {}
    }

    @Synchronized
    fun mark(target: String, offset: Long, length: Long, status: SectorStatus) {
        if (length <= 0) return
        val list = data.getOrPut(target) { mutableListOf() }
        // Remove overlapping ranges of lower or same "overwriteable" priority; BAD_PERMANENT is sticky
        val newList = mutableListOf<SectorRange>()
        for (r in list) {
            if (r.status == SectorStatus.BAD_PERMANENT && overlaps(r.offset, r.length, offset, length)) {
                // keep bad permanent, also keep the non-overlapping parts of incoming as bad? no - incoming can't overwrite
                newList.add(r)
            } else if (overlaps(r.offset, r.length, offset, length)) {
                // drop overlapping non-permanent range; we will re-add the new one below
            } else {
                newList.add(r)
            }
        }
        // If marking healthy but overlaps with BAD_PERMANENT, split around bad permanents
        if (status == SectorStatus.HEALTHY) {
            var remaining = mutableListOf(SectorRange(offset, length, status))
            val bads = newList.filter { it.status == SectorStatus.BAD_PERMANENT }
            for (b in bads) {
                val next = mutableListOf<SectorRange>()
                for (r in remaining) {
                    next.addAll(subtract(r, b))
                }
                remaining = next
            }
            newList.addAll(remaining)
        } else {
            newList.add(SectorRange(offset, length, status))
        }
        data[target] = newList
    }

    @Synchronized
    fun isBadPermanent(target: String, offset: Long, length: Long): Boolean {
        val list = data[target] ?: return false
        return list.any { it.status == SectorStatus.BAD_PERMANENT && overlaps(it.offset, it.length, offset, length) }
    }

    @Synchronized
    fun ranges(target: String): List<SectorRange> = data[target]?.toList() ?: emptyList()

    @Synchronized
    fun healthyRanges(target: String): List<SectorRange> =
        ranges(target).filter { it.status == SectorStatus.HEALTHY }

    @Synchronized
    fun badRanges(target: String): List<SectorRange> =
        ranges(target).filter { it.status == SectorStatus.BAD_PERMANENT }

    @Synchronized
    fun summary(): Map<String, Map<SectorStatus, Long>> {
        val out = mutableMapOf<String, Map<SectorStatus, Long>>()
        data.forEach { (k, v) ->
            val m = mutableMapOf<SectorStatus, Long>()
            v.forEach { r -> m[r.status] = (m[r.status] ?: 0L) + r.length }
            out[k] = m
        }
        return out
    }

    private fun overlaps(aOff: Long, aLen: Long, bOff: Long, bLen: Long): Boolean {
        val aEnd = aOff + aLen
        val bEnd = bOff + bLen
        return aOff < bEnd && bOff < aEnd
    }

    private fun subtract(r: SectorRange, bad: SectorRange): List<SectorRange> {
        if (!overlaps(r.offset, r.length, bad.offset, bad.length)) return listOf(r)
        val rEnd = r.offset + r.length
        val bEnd = bad.offset + bad.length
        val out = mutableListOf<SectorRange>()
        if (r.offset < bad.offset) out.add(SectorRange(r.offset, bad.offset - r.offset, r.status))
        if (bEnd < rEnd) out.add(SectorRange(bEnd, rEnd - bEnd, r.status))
        return out
    }
}
