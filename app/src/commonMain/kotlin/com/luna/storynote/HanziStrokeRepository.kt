package com.luna.storynote

import java.io.DataInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

data class StrokeOutline(
    val svgPath: String,
    val medianPoints: List<Pair<Float, Float>>, // normalized 0f..1f in screen top-down coords
)

data class HanziStrokeGlyph(
    val charCode: Int,
    val strokes: List<StrokeOutline>,
)

object HanziStrokeRepository {
    private val lock = Any()
    private var glyphsByCode: Map<Int, HanziStrokeGlyph>? = null

    fun ensureLoaded(openCompressedStream: () -> InputStream?) {
        if (glyphsByCode != null) return
        synchronized(lock) {
            if (glyphsByCode != null) return
            val rawStream = try {
                openCompressedStream()
            } catch (_: Exception) {
                null
            }
            if (rawStream == null) {
                glyphsByCode = emptyMap()
                return
            }
            glyphsByCode = try {
                GZIPInputStream(rawStream).use { gz ->
                    parseBinaryTable(DataInputStream(gz))
                }
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }

    fun getGlyph(ch: Char): HanziStrokeGlyph? =
        glyphsByCode?.get(ch.code)

    private fun parseBinaryTable(dis: DataInputStream): Map<Int, HanziStrokeGlyph> {
        val magic = ByteArray(4)
        dis.readFully(magic)
        if (String(magic, Charsets.US_ASCII) != "HZST") return emptyMap()

        val count = readIntLE(dis)
        if (count <= 0 || count > 20_000) return emptyMap()
        val map = HashMap<Int, HanziStrokeGlyph>(count * 4 / 3 + 4)

        for (i in 0 until count) {
            val code = readUShortLE(dis)
            val strokeCount = dis.readUnsignedByte()
            val strokes = ArrayList<StrokeOutline>(strokeCount)
            for (s in 0 until strokeCount) {
                val svgLen = readUShortLE(dis)
                val svgBytes = ByteArray(svgLen)
                dis.readFully(svgBytes)
                val svg = String(svgBytes, Charsets.US_ASCII)

                val ptCount = dis.readUnsignedByte()
                val pts = ArrayList<Pair<Float, Float>>(ptCount)
                for (p in 0 until ptCount) {
                    val qx = dis.readUnsignedByte()
                    val qy = dis.readUnsignedByte()
                    pts.add((qx / 255f) to (qy / 255f))
                }
                strokes.add(StrokeOutline(svg, pts))
            }
            map[code] = HanziStrokeGlyph(code, strokes)
        }
        return map
    }

    private fun readUShortLE(dis: DataInputStream): Int {
        val b0 = dis.readUnsignedByte()
        val b1 = dis.readUnsignedByte()
        return b0 or (b1 shl 8)
    }

    private fun readIntLE(dis: DataInputStream): Int {
        val b0 = dis.readUnsignedByte()
        val b1 = dis.readUnsignedByte()
        val b2 = dis.readUnsignedByte()
        val b3 = dis.readUnsignedByte()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}
