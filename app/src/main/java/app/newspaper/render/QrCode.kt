package app.newspaper.render

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.Base64

/** Builds the science-lead QR code as a vector SVG, styled like the reference design. */
object QrCode {

    fun matrix(text: String): BitMatrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
    )

    /** 2-module white quiet zone, one horizontal run per path segment, crisp edges. */
    fun svg(text: String): String {
        val m = matrix(text)
        val path = StringBuilder()
        for (y in 0 until m.height) {
            var x = 0
            while (x < m.width) {
                if (!m[x, y]) { x++; continue }
                val start = x
                while (x < m.width && m[x, y]) x++
                path.append("M").append(start).append(' ').append(y)
                    .append("h").append(x - start).append("v1h-").append(x - start).append('z')
            }
        }
        val w = m.width + 4
        val h = m.height + 4
        return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="-2 -2 $w $h" shape-rendering="crispEdges">""" +
            """<rect x="-2" y="-2" width="$w" height="$h" fill="#fff"/><path d="$path" fill="#1a1a1a"/></svg>"""
    }

    fun svgDataUri(text: String): String =
        "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg(text).toByteArray())
}
