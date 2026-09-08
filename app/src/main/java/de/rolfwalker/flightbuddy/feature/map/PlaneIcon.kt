package de.rolfwalker.flightbuddy.feature.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.toColorInt

/** Nose-north airliner, same silhouette as the PWA so icon-rotate is geographic heading. */
fun northPlaneBitmap(fillHex: String, strokeHex: String = "#FFFFFFFF"): Bitmap {
    val css = 28
    val pixelRatio = 2
    val size = css * pixelRatio
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.scale(pixelRatio.toFloat(), pixelRatio.toFloat())
    canvas.translate(css / 2f, css / 2f)
    val path = Path().apply {
        moveTo(0f, -11f)
        lineTo(2.1f, -2.2f)
        lineTo(11f, 3f)
        lineTo(11f, 5.2f)
        lineTo(2f, 2.4f)
        lineTo(1.5f, 8.2f)
        lineTo(4.4f, 10.6f)
        lineTo(4.4f, 12f)
        lineTo(0f, 10.4f)
        lineTo(-4.4f, 12f)
        lineTo(-4.4f, 10.6f)
        lineTo(-1.5f, 8.2f)
        lineTo(-2f, 2.4f)
        lineTo(-11f, 5.2f)
        lineTo(-11f, 3f)
        lineTo(-2.1f, -2.2f)
        close()
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3.5f
        color = strokeHex.toColorInt()
    }
    canvas.drawPath(path, paint)
    paint.style = Paint.Style.FILL
    paint.color = fillHex.toColorInt()
    canvas.drawPath(path, paint)
    return bmp
}
