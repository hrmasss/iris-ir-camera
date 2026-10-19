package io.github.hrmasss.iris.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Build
import androidx.annotation.StringRes
import io.github.hrmasss.iris.R

/**
 * Display looks. Most are a single colour matrix over luminance, which works on every API level.
 * Thermal maps luminance onto a heat ramp: an AGSL shader for the preview (API 33+) and a lookup
 * table for saved photos.
 */
enum class Look(@StringRes val label: Int, val matrix: ColorMatrix?) {
    Natural(R.string.filter_natural, null),
    Mono(R.string.filter_mono, tinted(1f, 1f, 1f)),
    Night(R.string.filter_night, tinted(0.25f, 1.15f, 0.3f)),
    Amber(R.string.filter_amber, tinted(1.15f, 0.72f, 0.25f)),
    Invert(R.string.filter_invert, inverted()),
    Thermal(R.string.filter_ironbow, null);

    val needsShader: Boolean get() = this == Thermal

    companion object {
        fun available(): List<Look> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) entries else entries.filterNot { it.needsShader }

        fun fromName(name: String?): Look = entries.firstOrNull { it.name == name } ?: Natural
    }
}

private const val LR = 0.299f
private const val LG = 0.587f
private const val LB = 0.114f

private fun tinted(r: Float, g: Float, b: Float) = ColorMatrix(
    floatArrayOf(
        LR * r, LG * r, LB * r, 0f, 0f,
        LR * g, LG * g, LB * g, 0f, 0f,
        LR * b, LG * b, LB * b, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    ),
)

private fun inverted() = ColorMatrix(
    floatArrayOf(
        -LR, -LG, -LB, 0f, 255f,
        -LR, -LG, -LB, 0f, 255f,
        -LR, -LG, -LB, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    ),
)

/** Heat ramp stops, shared by the shader and the CPU lookup table. */
private val thermalStops = listOf(
    0.00f to 0x000000,
    0.20f to 0x2A0A5E,
    0.40f to 0x9E1A8C,
    0.60f to 0xF0402A,
    0.80f to 0xFFB000,
    1.00f to 0xFFFFE8,
)

const val THERMAL_SHADER = """
uniform shader content;

half3 stop(int i) {
    if (i == 0) return half3(0.0, 0.0, 0.0);
    if (i == 1) return half3(0.165, 0.039, 0.369);
    if (i == 2) return half3(0.620, 0.102, 0.549);
    if (i == 3) return half3(0.941, 0.251, 0.165);
    if (i == 4) return half3(1.0, 0.690, 0.0);
    return half3(1.0, 1.0, 0.910);
}

half4 main(float2 p) {
    half4 c = content.eval(p);
    float l = clamp(dot(c.rgb, half3(0.299, 0.587, 0.114)), 0.0, 1.0) * 5.0;
    int i = int(floor(min(l, 4.999)));
    return half4(mix(stop(i), stop(i + 1), half(l - float(i))), c.a);
}
"""

private val thermalLut: IntArray by lazy {
    IntArray(256) { v ->
        val t = v / 255f
        val upper = thermalStops.indexOfFirst { it.first >= t }.coerceAtLeast(1)
        val (t0, c0) = thermalStops[upper - 1]
        val (t1, c1) = thermalStops[upper]
        val f = ((t - t0) / (t1 - t0)).coerceIn(0f, 1f)
        fun ch(shift: Int) = (((c0 shr shift) and 0xFF) * (1 - f) + ((c1 shr shift) and 0xFF) * f).toInt()
        (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/** Returns a new bitmap with [look] applied. [Look.Natural] returns the source unchanged. */
fun Look.applyTo(source: Bitmap): Bitmap {
    matrix?.let { m ->
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(source, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
        return out
    }
    if (this != Look.Thermal) return source
    val pixels = IntArray(source.width * source.height)
    source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
    for (i in pixels.indices) {
        val p = pixels[i]
        val luma = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
        pixels[i] = thermalLut[luma]
    }
    return Bitmap.createBitmap(pixels, source.width, source.height, Bitmap.Config.ARGB_8888)
}
