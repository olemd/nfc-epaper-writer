/**
 * @file IsoDep-based flasher for newer WaveShare NFC e-paper displays.
 *
 * Older displays (UID "WSDZ10m") speak a raw NfcA command set handled by the
 * bundled WaveShare JAR. Newer displays (e.g. UID "BMXR") are ISO 14443-4
 * (IsoDep) Type-4 tags using an APDU-based protocol reverse-engineered from
 * WaveShare's official app.
 *
 * This implements the 4-colour (black/white/red/yellow) write path for the
 * BMXR display: the panel is 296x128 at 2 bits/pixel, packed 4 pixels per byte
 * into a single 9472-byte buffer and written with the uncompressed command
 * (INS 0xD2). The device also supports an LZO-compressed command (0xD3), but
 * the raw path avoids porting the compressor and completes in a few seconds.
 */
package com.joshuatz.nfceinkwriter

import android.graphics.Bitmap
import android.graphics.Matrix
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log

/** Outcome of an IsoDep operation, surfaced to the UI. */
data class IsoDepResult(
    val success: Boolean,
    val message: String,
)

/** Parsed device descriptor fields we care about for encoding. */
private data class DeviceDescriptor(
    val width: Int,       // physical pixel width (296 for BMXR)
    val height: Int,      // physical pixel height (128 for BMXR)
    val refreshScan: Int, // 0 = vertical, 1 = horizontal
)

object IsoDepFlasher {
    private const val TAG = "IsoDepFlasher"

    // The panel's 2-bit colour codes (confirmed on hardware: 0=black, 1=white,
    // 3=red; 2=yellow completes the black/white/red/yellow palette).
    private const val IDX_BLACK = 0
    private const val IDX_WHITE = 1
    private const val IDX_YELLOW = 2
    private const val IDX_RED = 3

    fun isSupported(tag: Tag): Boolean = IsoDep.get(tag) != null

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "")
        return ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    private fun ByteArray.isOk(): Boolean =
        size >= 2 && this[size - 2] == 0x90.toByte() && this[size - 1] == 0x00.toByte()

    /**
     * Runs the handshake, writes [bitmap] to the display and refreshes.
     * Blocking — call off the main thread. [onProgress] reports 0..100.
     */
    fun flash4Color(tag: Tag, bitmap: Bitmap, onProgress: (Int) -> Unit): IsoDepResult {
        val isoDep = IsoDep.get(tag)
            ?: return IsoDepResult(false, "Tag is not IsoDep-capable")

        return try {
            isoDep.timeout = 50_000
            if (!isoDep.isConnected) isoDep.connect()

            // Handshake: select NDEF app, then read the self-describing descriptor.
            transceiveLogged(isoDep, "00A4040007D2760000850101", "SELECT")
            transceiveLogged(isoDep, "F0D801FE050000000000", "INIT")
            val descA = transceiveLogged(isoDep, "00D1000000", "DESC-A")

            // The BMXR family answers with an 0xA0 descriptor block. Other displays
            // (e.g. the 1.54" 200x200, which returns 0x6D00 here) speak a different
            // SSD1681-style command set over 74-prefixed APDUs.
            if (descA.isEmpty() || descA[0] != 0xA0.toByte()) {
                Log.i(TAG, "DESC-A not A0 (${descA.toHex()}); using SSD1681/1.54in path")
                return flash154(isoDep, bitmap, onProgress)
            }
            transceiveLogged(isoDep, "F0D8000005000000000E", "DESC-B")

            val desc = parseDescriptor(descA)
                ?: return IsoDepResult(false, "Could not parse device descriptor")
            Log.i(TAG, "Descriptor: ${desc.width}x${desc.height} scan=${desc.refreshScan}")

            // Single 2-bit buffer in the panel's native portrait geometry
            // (128x296, 32 bytes/row = 9472 bytes, 38 APDU rows).
            val buf = encode4Color(bitmap, desc)
            Log.i(TAG, "Encoded ${buf.size} bytes for ${desc.width}x${desc.height}")
            if (!writeBmpData(isoDep, buf, plane = 0, onProgress = onProgress)) {
                return IsoDepResult(false, "Write failed (see log for failing APDU)")
            }
            val refreshOk = refreshScreen(isoDep, plane = 0)
            onProgress(100)

            if (refreshOk) IsoDepResult(true, "Flashed 4-colour display!")
            else IsoDepResult(false, "Data written but refresh did not confirm")
        } catch (e: Exception) {
            Log.e(TAG, "Flash failed", e)
            IsoDepResult(false, "Error: ${e.message}")
        } finally {
            try {
                isoDep.close()
            } catch (e: Exception) {
                Log.w(TAG, "close() failed", e)
            }
        }
    }

    private fun transceiveLogged(isoDep: IsoDep, hex: String, label: String): ByteArray {
        val resp = isoDep.transceive(hexToBytes(hex))
        Log.d(TAG, "$label -> ${resp.toHex()}")
        return resp
    }

    /**
     * Parses the framebuffer geometry from descriptor part A. The panel's native
     * scan is portrait: width comes from the 0x0080 field (128) and height from
     * the 0x0250 field halved (592/2 = 296 — that field counts sub-columns). The
     * physical panel is landscape, so the source is rotated 90° before encoding.
     */
    private fun parseDescriptor(descA: ByteArray): DeviceDescriptor? {
        if (descA.size < 18 || descA[0] != 0xA0.toByte()) return null
        val hex = descA.toHex()
        val fieldA = hex.substring(10, 14).toInt(16) // 0x0250 = 592
        val fieldB = hex.substring(14, 18).toInt(16) // 0x0080 = 128
        val width = fieldB                           // 128
        val height = fieldA / 2                      // 296
        val a1Start = (descA[1].toInt() and 0xFF) + 2
        var scan = 1
        if (descA.size > a1Start + 2 && descA[a1Start] == 0xA1.toByte()) {
            scan = descA[a1Start + 2].toInt() and 0xFF
        }
        return DeviceDescriptor(width = width, height = height, refreshScan = scan)
    }

    /**
     * Quantises [bitmap] to the 4-colour palette (one 2-bit index per pixel) and
     * packs it into the panel's native portrait framebuffer (128x296), 4 pixels
     * per byte. The authored text is landscape, so it is rotated 90° to portrait
     * before scaling. Produces a single width*height/4-byte buffer.
     */
    private fun encode4Color(bitmap: Bitmap, desc: DeviceDescriptor): ByteArray {
        val w = desc.width  // 128
        val h = desc.height // 296
        val m = Matrix().apply { postRotate(270f) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        val scaled = Bitmap.createScaledBitmap(rotated, w, h, false)
        val indices = ByteArray(w * h)
        val rowPixels = IntArray(w)
        for (y in 0 until h) {
            scaled.getPixels(rowPixels, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                indices[y * w + x] = quantize(rowPixels[x]).toByte()
            }
        }
        return packIndices(indices, w, h)
    }

    /**
     * Packs 2-bit colour [indices] (row-major) into bytes, 4 pixels per byte,
     * forward scan matching WaveShare's color4horizontalScanning: the leftmost
     * of each 4-pixel group goes into the byte's high bits
     * (byte = p0<<6 | p1<<4 | p2<<2 | p3). Width is padded to a multiple of 4
     * (128 already is). Produces width*height/4 bytes.
     */
    private fun packIndices(indices: ByteArray, width: Int, height: Int): ByteArray {
        val pad = if (width % 4 != 0) 4 - (width % 4) else 0
        val paddedW = width + pad
        val bytesPerRow = paddedW / 4
        val out = ByteArray(bytesPerRow * height)
        for (y in 0 until height) {
            for (bx in 0 until bytesPerRow) {
                var b = 0
                for (k in 0 until 4) {
                    val x = bx * 4 + k
                    val idx = if (x < width) indices[y * width + x].toInt() and 0x03 else 0
                    b = b or (idx shl ((3 - k) * 2)) // leftmost pixel in high bits
                }
                out[y * bytesPerRow + bx] = b.toByte()
            }
        }
        return out
    }

    /** Nearest of black/white/red/yellow, returned as the panel's colour code. */
    private fun quantize(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val palette = arrayOf(
            Triple(0, 0, 0) to IDX_BLACK,
            Triple(255, 255, 255) to IDX_WHITE,
            Triple(255, 0, 0) to IDX_RED,
            Triple(255, 255, 0) to IDX_YELLOW,
        )
        var best = IDX_WHITE
        var bestDist = Int.MAX_VALUE
        for ((rgb, code) in palette) {
            val (pr, pg, pb) = rgb
            val dist = (r - pr) * (r - pr) + (g - pg) * (g - pg) + (b - pb) * (b - pb)
            if (dist < bestDist) {
                bestDist = dist
                best = code
            }
        }
        return best
    }

    /**
     * Uncompressed write of [data] to colour [plane]: one APDU per 250-byte row,
     * `F0 D2 [plane] [row] FA <250 bytes>`. Zero-padded to a whole row count.
     */
    private fun writeBmpData(isoDep: IsoDep, data: ByteArray, plane: Int, onProgress: (Int) -> Unit = {}): Boolean {
        val rowCount = (data.size + 249) / 250
        val padded = if (data.size < rowCount * 250) data.copyOf(rowCount * 250) else data
        val apdu = ByteArray(255)
        apdu[0] = 0xF0.toByte()
        apdu[1] = 0xD2.toByte()
        apdu[2] = plane.toByte()
        apdu[4] = 0xFA.toByte()
        for (row in 0 until rowCount) {
            apdu[3] = row.toByte()
            System.arraycopy(padded, row * 250, apdu, 5, 250)
            val resp = isoDep.transceive(apdu)
            if (!resp.isOk()) {
                Log.e(TAG, "Write row $row failed -> ${resp.toHex()}")
                return false
            }
            onProgress(row * 100 / rowCount)
        }
        return true
    }

    /**
     * Triggers the panel refresh and waits for completion, mirroring the
     * official app's two-phase sequence:
     *  1. Send `F0 D4 05 (plane|0x80) 00`.
     *  2. If the panel answers 0x6986/0x68C6 (kick-off phase), resend with INS
     *     parameter 0x85 until it acknowledges.
     *  3. Poll `F0 DE 00 00 01` until done (0x009000); 0x019000 means the
     *     physical refresh is still running (tens of seconds).
     */
    private fun refreshScreen(isoDep: IsoDep, plane: Int): Boolean {
        val p = (plane or 0x80).toByte()
        val start = byteArrayOf(0xF0.toByte(), 0xD4.toByte(), 0x05, p, 0x00)
        val startHex = isoDep.transceive(start).toHex()
        Log.d(TAG, "REFRESH start -> $startHex")

        when (startHex) {
            "698A" -> {
                Log.w(TAG, "Refresh rejected (698A)")
                return false
            }
            "6986", "68C6" -> {
                val kick = byteArrayOf(0xF0.toByte(), 0xD4.toByte(), 0x85.toByte(), p, 0x00)
                var acked = false
                for (i in 0 until 5) {
                    Thread.sleep(100)
                    val hex = isoDep.transceive(kick).toHex()
                    Log.d(TAG, "REFRESH kick -> $hex")
                    if (hex == "009000" || hex == "9000") {
                        acked = true
                        break
                    }
                }
                if (!acked) Log.w(TAG, "Refresh kick not acknowledged, polling anyway")
            }
            "019000" -> {
                Log.i(TAG, "Refresh acknowledged immediately")
                return true
            }
        }

        val poll = byteArrayOf(0xF0.toByte(), 0xDE.toByte(), 0x00, 0x00, 0x01)
        for (i in 0 until 1000) {
            val hex = isoDep.transceive(poll).toHex()
            when (hex) {
                "009000" -> {
                    Log.i(TAG, "Refresh complete")
                    return true
                }
                "019000" -> Thread.sleep(100)
                else -> {
                    Log.w(TAG, "Refresh poll -> $hex")
                    return false
                }
            }
        }
        return false
    }

    // ----- 1.54" 200x200 black/white/red (SSD1681 over 74-prefixed APDUs) -----

    private fun bytes(vararg ints: Int): ByteArray = ByteArray(ints.size) { ints[it].toByte() }

    /** Writes an SSD1681 controller command byte, then (optionally) its data bytes. */
    private fun ctrl(isoDep: IsoDep, cmd: Int, data: ByteArray? = null): Boolean {
        val r1 = isoDep.transceive(bytes(0x74, 0x99, 0x00, 0x0D, 0x01, cmd))
        if (!r1.isOk()) {
            Log.e(TAG, "154 cmd %02X -> %s".format(cmd, r1.toHex())); return false
        }
        if (data != null) {
            val r2 = isoDep.transceive(bytes(0x74, 0x9A, 0x00, 0x0E, data.size) + data)
            if (!r2.isOk()) {
                Log.e(TAG, "154 cmd %02X data -> %s".format(cmd, r2.toHex())); return false
            }
        }
        return true
    }

    /** Streams a 5000-byte RAM plane in 250-byte chunks (74 9A 00 0E FA + data). */
    private fun writeRam154(isoDep: IsoDep, plane: ByteArray, onProgress: (Int) -> Unit): Boolean {
        val chunks = plane.size / 250
        val header = bytes(0x74, 0x9A, 0x00, 0x0E, 0xFA)
        for (i in 0 until chunks) {
            val resp = isoDep.transceive(header + plane.copyOfRange(i * 250, i * 250 + 250))
            if (!resp.isOk()) {
                Log.e(TAG, "154 RAM chunk $i -> ${resp.toHex()}"); return false
            }
            onProgress(i * 100 / chunks)
        }
        return true
    }

    /**
     * Flashes the 1.54" 200x200 4-colour (black/white/red/yellow) display. Auth,
     * a short config register sequence, a single 2-bit RAM buffer (10000 bytes),
     * then the refresh trigger and busy-poll. The panel is NFC-powered, so the
     * poll (and the 10s settle) must keep the field alive through the refresh.
     */
    private fun flash154(isoDep: IsoDep, bitmap: Bitmap, onProgress: (Int) -> Unit): IsoDepResult {
        return try {
            // Auth with the app's fixed unlock key + init.
            isoDep.transceive(bytes(0x74, 0xB1, 0x00, 0x00, 0x08, 0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77))
            isoDep.transceive(bytes(0x74, 0x97, 0x00, 0x08, 0x00)); Thread.sleep(50)
            isoDep.transceive(bytes(0x74, 0x97, 0x01, 0x08, 0x00)); Thread.sleep(200)
            isoDep.transceive(bytes(0x74, 0x00, 0x15, 0x00, 0x00)); Thread.sleep(100)

            // Panel config registers.
            ctrl(isoDep, 0xE0, bytes(0x02))
            ctrl(isoDep, 0xE6, bytes(0x5D))
            ctrl(isoDep, 0xA5, bytes(0x00))
            Thread.sleep(100)

            // Begin RAM write, stream the 2-bit image, then commit.
            isoDep.transceive(bytes(0x74, 0x01, 0x15, 0x01, 0x00))
            val buf = encode154(bitmap)
            Log.i(TAG, "1.54in encoded ${buf.size} bytes")
            if (!writeRam154(isoDep, buf) { onProgress(it) }) {
                return IsoDepResult(false, "1.54in: RAM write failed")
            }
            Thread.sleep(50)

            // Trigger refresh; the panel is busy while the status byte reads 0.
            isoDep.transceive(bytes(0x74, 0x02, 0x15, 0x02, 0x00))
            Thread.sleep(10000)
            var polls = 0
            while (polls < 40) {
                polls++
                val r = isoDep.transceive(bytes(0x74, 0x9B, 0x00, 0x0F, 0x01))
                if (r.isEmpty() || r[0].toInt() != 0) break
                Thread.sleep(400)
            }
            // Power-down registers.
            ctrl(isoDep, 0x02, bytes(0x00)); Thread.sleep(200)
            ctrl(isoDep, 0x07, bytes(0xA5))

            onProgress(100)
            IsoDepResult(true, "Flashed 1.54\" display!")
        } catch (e: Exception) {
            Log.e(TAG, "1.54in flash failed", e)
            IsoDepResult(false, "1.54in error: ${e.message}")
        }
    }

    /**
     * Quantises [bitmap] to the 4-colour palette and packs it into the 1.54"
     * panel's single 2-bit RAM buffer: 200x200, 4 pixels per byte, 50 bytes/row
     * (10000 bytes). Same packing/palette as the BMXR path.
     */
    private fun encode154(bitmap: Bitmap): ByteArray {
        val size = 200
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, false)
        val indices = ByteArray(size * size)
        val rowPixels = IntArray(size)
        for (y in 0 until size) {
            scaled.getPixels(rowPixels, 0, size, 0, y, size, 1)
            for (x in 0 until size) {
                indices[y * size + x] = quantize(rowPixels[x]).toByte()
            }
        }
        return packIndices(indices, size, size)
    }
}
