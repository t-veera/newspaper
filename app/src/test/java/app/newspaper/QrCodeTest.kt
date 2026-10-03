package app.newspaper

import app.newspaper.render.QrCode
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class QrCodeTest {

    private val url = "https://example.org/science/single-cell-magnetometry"

    @Test
    fun dataUriIsBase64Svg() {
        val uri = QrCode.svgDataUri(url)
        assertTrue(uri.startsWith("data:image/svg+xml;base64,"))
        val svg = String(Base64.getDecoder().decode(uri.substringAfter(",")))
        assertTrue(svg.startsWith("<svg") && svg.contains("<path d=\"M"))
    }

    @Test
    fun matrixDecodesBackToUrl() {
        val m = QrCode.matrix(url)
        val scale = 4
        val quiet = 4 * scale
        val w = m.width * scale + 2 * quiet
        val pixels = IntArray(w * w) { 0xFFFFFF }
        for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y]) {
            for (dy in 0 until scale) for (dx in 0 until scale) {
                pixels[(quiet + y * scale + dy) * w + quiet + x * scale + dx] = 0
            }
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, w, pixels)))
        assertEquals(url, QRCodeReader().decode(bitmap).text)
    }
}
