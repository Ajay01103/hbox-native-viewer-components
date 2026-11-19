package com.margelo.nitro.hboxpdf.editor

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max

/** Colours, metrics and small helpers shared by every native PDF surface. */
internal object PdfUi {
  const val ACCENT = 0xFF1473E6.toInt()
  const val ACCENT_SOFT = 0xFFECF3FE.toInt()
  const val INK = 0xFF10141A.toInt()
  const val INK_SOFT = 0xFF49515C.toInt()
  const val MUTED = 0xFF8B929C.toInt()
  const val SURFACE = 0xFFFFFFFF.toInt()
  const val CANVAS = 0xFFF1F2F5.toInt()
  const val DIVIDER = 0xFFECEEF2.toInt()
  const val DANGER = 0xFFE5484D.toInt()
  const val AMBER = 0xFFF5A623.toInt()

  val HIGHLIGHTS = intArrayOf(
    0xFFFFD84D.toInt(),
    0xFF7BE3A1.toInt(),
    0xFF7CC4FF.toInt(),
    0xFFFFB2A6.toInt(),
  )

  val INKS = intArrayOf(
    0xFF10141A.toInt(),
    0xFFE5484D.toInt(),
    0xFF2563EB.toInt(),
    0xFF12A150.toInt(),
  )
}

internal val Resources.densityValue: Float
  get() = displayMetrics.density

internal fun Context.dpf(value: Float): Float = value * resources.densityValue

internal fun Context.dpi(value: Float): Int = (value * resources.densityValue).toInt().coerceAtLeast(1)

internal fun View.dpf(value: Float): Float = value * resources.displayMetrics.density

internal fun View.dpi(value: Float): Int = (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

internal fun rounded(
  color: Int,
  radiusDp: Float,
  context: Context,
  strokeColor: Int = 0,
  strokeWidthDp: Float = 0f,
): GradientDrawable = GradientDrawable().apply {
  shape = GradientDrawable.RECTANGLE
  setColor(color)
  cornerRadius = context.dpf(radiusDp)
  if (strokeColor != 0 && strokeWidthDp > 0f) setStroke(context.dpi(strokeWidthDp), strokeColor)
}

internal fun Context.label(
  text: String,
  sizeSp: Float,
  color: Int = PdfUi.INK,
  style: Int = Typeface.NORMAL,
): TextView = TextView(this).apply {
  this.text = text
  textSize = sizeSp
  setTextColor(color)
  typeface = Typeface.create("sans-serif", style)
  includeFontPadding = false
  setLineSpacing(0f, 1.12f)
}

internal fun View.space(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0) {
  val params = layoutParams ?: return
  if (params is ViewGroup.MarginLayoutParams) {
    params.setMargins(left, top, right, bottom)
    layoutParams = params
  }
}

internal enum class PdfIcon {
  BACK,
  CHECK,
  UNDO,
  ANNOTATE,
  SEARCH,
  SHARE,
  MORE,
  SPEAKER,
  COMMENT,
  HIGHLIGHT,
  DRAW,
  SIGN,
  EDIT,
  TOOLS,
  PLUS,
  TRASH,
  COPY,
  PENCIL,
  CHEVRON_LEFT,
  CHEVRON_RIGHT,
  CLOSE,
  SEND,
  ROTATE,
  FIT_WIDTH,
  FIT_PAGE,
}

/**
 * Hand-drawn vector icons on a 24 unit grid.
 *
 * The icons are painted with a single stroked path each, rounded caps and a
 * uniform optical weight, which keeps the chrome crisp at every density and
 * avoids shipping a set of PNGs at five resolutions.
 */
internal object PdfIconPainter {
  private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
  }
  private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

  fun draw(canvas: Canvas, icon: PdfIcon, cx: Float, cy: Float, size: Float, color: Int, strokeWidth: Float) {
    if (size <= 0f) return
    val scale = size / 24f
    canvas.save()
    canvas.translate(cx - size / 2f, cy - size / 2f)
    canvas.scale(scale, scale)
    stroke.color = color
    stroke.strokeWidth = (strokeWidth / scale).coerceAtLeast(0.6f)
    fill.color = color
    when (icon) {
      PdfIcon.BACK -> {
        val p = Path()
        p.moveTo(20f, 12f); p.lineTo(5f, 12f)
        p.moveTo(10.5f, 6f); p.lineTo(4.5f, 12f); p.lineTo(10.5f, 18f)
        canvas.drawPath(p, stroke)
      }
      PdfIcon.CHECK -> {
        val p = Path()
        p.moveTo(4.5f, 12.5f); p.lineTo(9.5f, 17.5f); p.lineTo(19.5f, 6.5f)
        canvas.drawPath(p, stroke)
      }
      PdfIcon.UNDO -> {
        val p = Path()
        p.moveTo(8.5f, 4.8f); p.lineTo(4f, 9.2f); p.lineTo(8.5f, 13.6f)
        p.moveTo(4f, 9.2f); p.lineTo(13.5f, 9.2f)
        p.cubicTo(18.4f, 9.2f, 21f, 12f, 20f, 16.2f)
        p.cubicTo(19.7f, 17.6f, 19f, 18.7f, 18.2f, 19.5f)
        canvas.drawPath(p, stroke)
      }
      PdfIcon.ANNOTATE -> {
        val p = Path()
        p.moveTo(6.4f, 15.2f)
        p.lineTo(13.2f, 5.6f)
        p.lineTo(18.2f, 9.1f)
        p.lineTo(11.4f, 18.7f)
        p.close()
        p.moveTo(5.6f, 16.4f); p.lineTo(4.2f, 20.2f); p.lineTo(8.1f, 18.9f)
        p.moveTo(15.6f, 14.4f); p.lineTo(20.6f, 14.4f)
        p.moveTo(15.6f, 18.4f); p.lineTo(20.6f, 18.4f)
        canvas.drawPath(p, stroke)
      }
      PdfIcon.SEARCH -> {
        canvas.drawCircle(10.4f, 10.4f, 6.1f, stroke)
        val p = Path()
        p.moveTo(14.9f, 14.9f); p.lineTo(20.4f, 20.4f)
        canvas.drawPath(p, stroke)
      }
      PdfIcon.SHARE -> {
        canvas.drawCircle(17.6f, 5.6f, 2.4f, stroke)
        canvas.drawCircle(6.4f, 12f, 2.4f, stroke)
        canvas.drawCircle(17.6f, 18.4f, 2.4f, stroke)
        val p = Path()
        p.moveTo(8.5f, 10.9f); p.lineTo(15.6f, 6.7f)
        p.moveTo(8.5f, 13.1f); p.lineTo(15.6f, 17.3f)
        canvas.drawPath(p, stroke)
      }
      PdfIcon.MORE -> {
        canvas.drawCircle(12f, 5.4f, 1.55f, fill)
        canvas.drawCircle(12f, 12f, 1.55f, fill)
        canvas.drawCircle(12f, 18.6f, 1.55f, fill)
      }
      PdfIcon.SPEAKER -> {
        val p = Path()
        p.moveTo(3.6f, 9.2f)
        p.lineTo(7.2f, 9.2f)
        p.lineTo(12.2f, 4.4f)
        p.lineTo(12.2f, 19.6f)
        p.lineTo(7.2f, 14.8f)
        p.lineTo(3.6f, 14.8f)
        p.close()
        canvas.drawPath(p, stroke)
        canvas.drawArc(RectF(12.2f, 7.4f, 18.6f, 16.6f), -62f, 124f, false, stroke)
        canvas.drawArc(RectF(12.2f, 5.2f, 21.4f, 18.8f), -58f, 116f, false, stroke)
      }
      PdfIcon.COMMENT -> {
        val p = Path()
        p.addRoundRect(RectF(3.4f, 4.4f, 20.6f, 16.4f), 3.6f, 3.6f, Path.Direction.CW)
        p.moveTo(8.4f, 16.4f); p.lineTo(8.4f, 20.8f); p.lineTo(13.2f, 16.4f)
        canvas.drawPath(p, stroke)
      }
      PdfIcon.HIGHLIGHT -> {
        val p = Path()
        p.moveTo(7.2f, 15.2f)
        p.lineTo(13.8f, 5.8f)
        p.lineTo(18.6f, 9.2f)
        p.lineTo(12f, 18.6f)
        p.close()
        p.moveTo(6.2f, 16.2f); p.lineTo(4.6f, 20.4f); p.lineTo(8.9f, 18.8f)
        p.moveTo(3.6f, 21.4f); p.lineTo(10.4f, 21.4f)
        p.moveTo(13.4f, 21.4f); p.lineTo(20.4f, 21.4f)
        canvas.drawPath(p, stroke)
      }
      PdfIcon.DRAW -> {
        val p = Path()
        p.moveTo(12f, 3.6f)
        p.lineTo(17.6f, 9.2f)
        p.lineTo(9f, 17.8f)
        p.lineTo(4.2f, 20f)
        p.lineTo(6.4f, 15.2f)
        p.close()
        p.moveTo(12f, 3.6f); p.lineTo(17.6f, 9.2f)
        canvas.drawPath(p, stroke)
      }
      PdfIcon.SIGN -> {
        val p = Path()
        p.moveTo(4.6f, 15.4f)
        p.cubicTo(7.2f, 8.4f, 10.2f, 8.6f, 10.6f, 13.6f)
        p.cubicTo(11f, 18.6f, 14.4f, 19.2f, 19.4f, 10.8f)
        p.moveTo(4.4f, 21f); p.lineTo(19.6f, 21f)
        canvas.drawPath(p, stroke)
      }
      PdfIcon.EDIT -> {
        val p = Path()
        p.moveTo(3.6f, 20.4f)
        p.lineTo(8.2f, 19.4f)
        p.lineTo(19.6f, 8f)
        p.lineTo(16f, 4.4f)
        p.lineTo(4.6f, 15.8f)
        p.close()
        p.moveTo(14f, 6.4f); p.lineTo(17.6f, 10f)
        canvas.drawPath(p, stroke)
      }
      PdfIcon.TOOLS -> {
        canvas.drawRoundRect(RectF(3.6f, 3.6f, 10.4f, 10.4f), 2.2f, 2.2f, stroke)
        canvas.drawRoundRect(RectF(13.6f, 3.6f, 20.4f, 10.4f), 2.2f, 2.2f, stroke)
        canvas.drawRoundRect(RectF(3.6f, 13.6f, 10.4f, 20.4f), 2.2f, 2.2f, stroke)
        canvas.drawRoundRect(RectF(13.6f, 13.6f, 20.4f, 20.4f), 2.2f, 2.2f, stroke)
      }
      PdfIcon.PLUS -> {
        val p = Path()
        p.moveTo(12f, 5.4f); p.lineTo(12f, 18.6f)
        p.moveTo(5.4f, 12f); p.lineTo(18.6f, 12f)
        canvas.drawPath(p, stroke)
      }
      PdfIcon.TRASH -> {
        val p = Path()
        p.moveTo(3.8f, 6.6f); p.lineTo(20.2f, 6.6f)
        p.moveTo(9.2f, 6.6f); p.lineTo(9.2f, 4.2f); p.lineTo(14.8f, 4.2f); p.lineTo(14.8f, 6.6f)
        p.moveTo(5.8f, 6.6f); p.lineTo(6.8f, 20.4f); p.lineTo(17.2f, 20.4f); p.lineTo(18.2f, 6.6f)
        p.moveTo(10.2f, 10.2f); p.lineTo(10.2f, 17f)
        p.moveTo(13.8f, 10.2f); p.lineTo(13.8f, 17f)
        canvas.drawPath(p, stroke)
      }
      PdfIcon.COPY -> {
        canvas.drawRoundRect(RectF(8.4f, 8.4f, 20.2f, 20.2f), 2.6f, 2.6f, stroke)
        val p = Path()
        p.moveTo(15.6f, 5.2f)
        p.lineTo(6f, 5.2f)
        p.lineTo(6f, 15.4f)
        canvas.drawPath(p, stroke)
      }
      PdfIcon.PENCIL -> {
        val p = Path()
        p.moveTo(4.4f, 19.6f)
        p.lineTo(5.4f, 15.4f)
        p.lineTo(16f, 4.8f)
        p.lineTo(19.2f, 8f)
        p.lineTo(8.6f, 18.6f)
        p.close()
        p.moveTo(13.6f, 7.2f); p.lineTo(16.8f, 10.4f)
        canvas.drawPath(p, stroke)
      }
      PdfIcon.CHEVRON_LEFT -> {
        val p = Path()
        p.moveTo(14.8f, 5.4f); p.lineTo(8.4f, 12f); p.lineTo(14.8f, 18.6f)
        canvas.drawPath(p, stroke)
      }
      PdfIcon.CHEVRON_RIGHT -> {
        val p = Path()
        p.moveTo(9.2f, 5.4f); p.lineTo(15.6f, 12f); p.lineTo(9.2f, 18.6f)
        canvas.drawPath(p, stroke)
      }
      PdfIcon.CLOSE -> {
        val p = Path()
        p.moveTo(6f, 6f); p.lineTo(18f, 18f)
        p.moveTo(18f, 6f); p.lineTo(6f, 18f)
        canvas.drawPath(p, stroke)
      }
      PdfIcon.SEND -> {
        val p = Path()
        p.moveTo(5f, 19f); p.lineTo(19f, 5f)
        p.moveTo(10.4f, 5f); p.lineTo(19f, 5f); p.lineTo(19f, 13.6f)
        canvas.drawPath(p, stroke)
      }
      PdfIcon.ROTATE -> {
        canvas.drawArc(RectF(4.4f, 5.2f, 19.6f, 19.6f), -70f, 250f, false, stroke)
        val p = Path()
        p.moveTo(16.2f, 2.8f); p.lineTo(20.6f, 6.4f); p.lineTo(15.6f, 8.4f)
        canvas.drawPath(p, stroke)
      }
      PdfIcon.FIT_WIDTH -> {
        val p = Path()
        p.moveTo(3.6f, 12f); p.lineTo(20.4f, 12f)
        p.moveTo(3.6f, 12f); p.lineTo(7.4f, 8.2f)
        p.moveTo(3.6f, 12f); p.lineTo(7.4f, 15.8f)
        p.moveTo(20.4f, 12f); p.lineTo(16.6f, 8.2f)
        p.moveTo(20.4f, 12f); p.lineTo(16.6f, 15.8f)
        p.moveTo(3.6f, 4.6f); p.lineTo(20.4f, 4.6f)
        p.moveTo(3.6f, 19.4f); p.lineTo(20.4f, 19.4f)
        canvas.drawPath(p, stroke)
      }
      PdfIcon.FIT_PAGE -> {
        canvas.drawRoundRect(RectF(4.2f, 3.6f, 19.8f, 20.4f), 2.4f, 2.4f, stroke)
        val p = Path()
        p.moveTo(8f, 12f); p.lineTo(16f, 12f)
        p.moveTo(8f, 12f); p.lineTo(10.6f, 9.4f)
        p.moveTo(8f, 12f); p.lineTo(10.6f, 14.6f)
        p.moveTo(16f, 12f); p.lineTo(13.4f, 9.4f)
        p.moveTo(16f, 12f); p.lineTo(13.4f, 14.6f)
        canvas.drawPath(p, stroke)
      }
    }
    canvas.restore()
  }
}

/** A single chrome icon, sized by its layout box. */
internal class PdfIconView(context: Context) : View(context) {
  var icon: PdfIcon = PdfIcon.BACK
    set(value) {
      field = value
      invalidate()
    }

  var color: Int = PdfUi.INK
    set(value) {
      field = value
      invalidate()
    }

  /** Stroke width in dp at the 24 unit icon grid. */
  var weight: Float = 1.5f
    set(value) {
      field = value
      invalidate()
    }

  init {
    isClickable = false
    isFocusable = false
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    PdfIconPainter.draw(
      canvas,
      icon,
      width / 2f,
      height / 2f,
      minOf(width, height).toFloat() * 0.82f,
      color,
      weight * resources.displayMetrics.density,
    )
  }
}

/**
 * A 44-48dp touch target around one icon.
 *
 * `drawableStateChanged` fades the glyph instead of swapping a ripple: the app
 * bar sits on white, where a ripple reads as a grey smudge.
 */
internal class PdfIconButton(context: Context) : FrameLayout(context) {
  val iconView: PdfIconView = PdfIconView(context)

  init {
    clipChildren = false
    isClickable = true
    isFocusable = true
    val size = dpi(44f)
    addView(iconView, LayoutParams(dpi(22f), dpi(22f), Gravity.CENTER))
    layoutParams = ViewGroup.LayoutParams(size, size)
  }

  override fun drawableStateChanged() {
    super.drawableStateChanged()
    alpha = if (isPressed) 0.45f else 1f
  }
}

/** Bottom-strip entry: icon above a small label. */
internal class PdfToolButton(context: Context) : LinearLayout(context) {
  val iconView = PdfIconView(context)
  val label = context.label("", 10.5f, PdfUi.INK_SOFT, Typeface.NORMAL)
  private var activeColor: Int = PdfUi.ACCENT
  private var idleColor: Int = PdfUi.INK_SOFT

  init {
    orientation = VERTICAL
    gravity = Gravity.CENTER_HORIZONTAL
    isClickable = true
    isFocusable = true
    setPadding(dpi(2f), dpi(7f), dpi(2f), dpi(8f))
    addView(iconView, LayoutParams(dpi(23f), dpi(23f)))
    addView(label, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
      topMargin = dpi(5f)
    })
    label.gravity = Gravity.CENTER
    iconView.color = idleColor
    setState(false)
  }

  fun bind(icon: PdfIcon, text: String) {
    iconView.icon = icon
    label.text = text
  }

  fun setState(active: Boolean) {
    iconView.color = if (active) activeColor else idleColor
    label.setTextColor(if (active) activeColor else idleColor)
    label.typeface = Typeface.create("sans-serif", if (active) Typeface.BOLD else Typeface.NORMAL)
    background = if (active) rounded(PdfUi.ACCENT_SOFT, 14f, context) else null
  }

  override fun setSelected(selected: Boolean) {
    super.setSelected(selected)
    setState(selected)
  }

  override fun drawableStateChanged() {
    super.drawableStateChanged()
    alpha = if (isPressed) 0.5f else 1f
  }
}

/** Circular colour chip with a selection ring. */
internal class PdfSwatch(context: Context) : View(context) {
  var color: Int = Color.YELLOW
    set(value) {
      field = value
      invalidate()
    }

  var checked: Boolean = false
    set(value) {
      field = value
      invalidate()
    }

  /** Palette swatches are rounded squares; the tool strip uses circles. */
  var square: Boolean = false

  private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = dpf(1.4f)
  }

  init {
    isClickable = true
    isFocusable = true
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    if (square) {
      val inset = dpf(4.5f)
      val box = RectF(inset, inset, width - inset, height - inset)
      fillPaint.color = color
      canvas.drawRoundRect(box, dpf(7f), dpf(7f), fillPaint)
      ringPaint.color = if (checked) PdfUi.ACCENT else 0x26000000
      ringPaint.strokeWidth = if (checked) dpf(2.2f) else dpf(1f)
      val ring = RectF(box)
      ring.inset(-dpf(2.6f), -dpf(2.6f))
      canvas.drawRoundRect(ring, dpf(9f), dpf(9f), ringPaint)
      if (checked) {
        val p = Path()
        p.moveTo(width * 0.32f, height * 0.52f)
        p.lineTo(width * 0.45f, height * 0.66f)
        p.lineTo(width * 0.70f, height * 0.36f)
        ringPaint.color = Color.WHITE
        ringPaint.strokeWidth = dpf(2.2f)
        canvas.drawPath(p, ringPaint)
      }
      return
    }
    val radius = minOf(width, height) / 2f - dpf(4.5f)
    val cx = width / 2f
    val cy = height / 2f
    fillPaint.color = color
    canvas.drawCircle(cx, cy, radius, fillPaint)
    ringPaint.color = if (checked) PdfUi.ACCENT else 0x33000000
    ringPaint.strokeWidth = if (checked) dpf(2f) else dpf(0.8f)
    canvas.drawCircle(cx, cy, radius + dpf(3.4f), ringPaint)
  }

  override fun drawableStateChanged() {
    super.drawableStateChanged()
    alpha = if (isPressed) 0.6f else 1f
  }
}

/** Pen width / highlight height stop, drawn as a dot that grows with size. */
/** Markup palette: six hues × three shades, like the reference sheet. */
/** Pen thickness presets offered by the draw sheet. */
internal const val STROKE_STEPS = 6

internal val MARKUP_COLORS = intArrayOf(
  0xFF111111.toInt(),
  0xFF34C759.toInt(),
  0xFFFFE34D.toInt(),
  0xFFFF9500.toInt(),
  0xFFD93A33.toInt(),
  0xFF8E44AD.toInt(),
  0xFF32C7D9.toInt(),
  0xFF12775B.toInt(),
  0xFFF4EF9B.toInt(),
  0xFFF7CBA4.toInt(),
  0xFFF48FB1.toInt(),
  0xFFE56BE8.toInt(),
  0xFFFFFFFF.toInt(),
  0xFFD9D9D9.toInt(),
  0xFFB0B0B0.toInt(),
  0xFF7A7A7A.toInt(),
  0xFF3C3C3C.toInt(),
  0xFF2F6BFF.toInt(),
)

/** Spoken names for the palette, used as each swatch's accessibility label. */
internal object PdfColorName {
  private val names = mapOf(
    0x111111 to "Black",
    0x34C759 to "Green",
    0xFFE34D to "Yellow",
    0xFF9500 to "Orange",
    0xD93A33 to "Red",
    0x8E44AD to "Purple",
    0x32C7D9 to "Cyan",
    0x12775B to "Teal",
    0xF4EF9B to "Pale yellow",
    0xF7CBA4 to "Peach",
    0xF48FB1 to "Pink",
    0xE56BE8 to "Magenta",
    0xFFFFFF to "White",
    0xD9D9D9 to "Light grey",
    0xB0B0B0 to "Grey",
    0x7A7A7A to "Dark grey",
    0x3C3C3C to "Charcoal",
    0x2F6BFF to "Blue",
  )

  fun of(color: Int): String {
    val rgb = color and 0x00FFFFFF
    return names[rgb] ?: String.format("Colour %06X", rgb)
  }
}

/**
 * Opacity slider for the markup sheet: a checkerboard behind a transparent→tint
 * gradient, so the chosen level reads the same way it will print.
 */
internal class PdfOpacityBar(context: Context) : View(context) {
  var value: Float = 0.4f
    set(next) {
      field = next.coerceIn(0f, 1f)
      invalidate()
    }
  var tint: Int = Color.YELLOW
    set(next) {
      field = next
      rebuildGradient()
      invalidate()
    }
  var onChange: ((Float) -> Unit)? = null

  private val density = resources.displayMetrics.density
  private val checkerPaint = Paint()
  private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
  private var gradient: android.graphics.LinearGradient? = null
  private val trackHeight = 26f * density
  private val inset = 10f * density

  init {
    isClickable = true
  }

  private fun rebuildGradient() {
    if (width <= 0) return
    val start = inset
    val end = width - inset
    gradient = android.graphics.LinearGradient(
      start, 0f, end, 0f,
      intArrayOf(0x00FFFFFF or (tint and 0x00FFFFFF), 0xFF000000.toInt() or (tint and 0x00FFFFFF)),
      null,
      android.graphics.Shader.TileMode.CLAMP,
    )
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    rebuildGradient()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val cy = height / 2f
    val start = inset
    val end = width - inset
    val top = cy - trackHeight / 2f
    val bottom = cy + trackHeight / 2f
    val radius = trackHeight / 2f
    val track = RectF(start, top, end, bottom)

    canvas.save()
    val clip = Path().apply { addRoundRect(track, radius, radius, Path.Direction.CW) }
    canvas.clipPath(clip)
    // Checkerboard makes the transparency legible.
    val cell = 7f * density
    var y = top
    var row = 0
    while (y < bottom) {
      var x = start
      var col = 0
      while (x < end) {
        checkerPaint.color = if ((row + col) % 2 == 0) 0xFFFFEFC9.toInt() else 0xFFF7DFA5.toInt()
        canvas.drawRect(x, y, x + cell, y + cell, checkerPaint)
        x += cell
        col++
      }
      y += cell
      row++
    }
    gradient?.let { shader ->
      trackPaint.shader = shader
      trackPaint.alpha = 255
      canvas.drawRect(track, trackPaint)
      trackPaint.shader = null
    }
    canvas.restore()

    ringPaint.color = 0x22000000
    ringPaint.strokeWidth = 1f * density
    canvas.drawRoundRect(track, radius, radius, ringPaint)

    val thumbX = start + (end - start) * value
    val thumbRadius = 10f * density
    thumbPaint.color = Color.WHITE
    canvas.drawCircle(thumbX, cy, thumbRadius, thumbPaint)
    ringPaint.color = 0x66000000
    ringPaint.strokeWidth = 1.6f * density
    canvas.drawCircle(thumbX, cy, thumbRadius, ringPaint)
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
        val start = inset
        val end = width - inset
        if (end <= start) return true
        value = ((event.x - start) / (end - start)).coerceIn(0f, 1f)
        onChange?.invoke(value)
        return true
      }
      MotionEvent.ACTION_UP -> return true
    }
    return super.onTouchEvent(event)
  }
}

/**
 * Bottom sheet painted natively: scrim, rounded panel, drag handle and a
 * decelerating slide. Content is swapped in by the editor.
 *
 * The panel keeps a single `translationY` that both the open/close animation
 * and the drag-to-dismiss gesture drive, so interrupting an animation with a
 * finger never leaves the sheet half way.
 */
internal class PdfSheet(context: Context) : FrameLayout(context) {
  val content = LinearLayout(context)
  var onDismiss: (() -> Unit)? = null

  private val scrim = View(context)
  private val panel = FrameLayout(context)
  private val handle = View(context)
  private val scroll = android.widget.ScrollView(context)
  private var animator: ValueAnimator? = null
  private var opened = false
  private var modal = true
  private var basePaddingBottom = 0

  init {
    visibility = GONE
    setBackgroundColor(Color.TRANSPARENT)
    // The editor's tool-options bar carries elevation, and a FrameLayout draws
    // elevated children above their siblings, so the sheet needs its own to stay
    // on top of it.
    elevation = dpf(30f)
    addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    scrim.setBackgroundColor(0x5C101418)
    scrim.setOnClickListener { if (modal) close() }

    panel.background = GradientDrawable().apply {
      setColor(PdfUi.SURFACE)
      cornerRadii = floatArrayOf(
        dpf(26f), dpf(26f),
        dpf(26f), dpf(26f),
        0f, 0f,
        0f, 0f,
      )
    }
    panel.elevation = dpf(18f)
    addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

    // A generous transparent handle: the visible bar stays slim but the drag
    // target is large enough to grab with a thumb.
    handle.setBackgroundColor(Color.TRANSPARENT)
    panel.addView(handle, LayoutParams(dpi(56f), dpi(24f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
      topMargin = dpi(2f)
    })
    val grip = View(context)
    grip.background = rounded(0xFFD7DAE0.toInt(), 2f, context)
    panel.addView(grip, LayoutParams(dpi(40f), dpi(4f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
      topMargin = dpi(11f)
    })

    content.orientation = LinearLayout.VERTICAL
    basePaddingBottom = dpi(14f)
    content.setPadding(dpi(18f), dpi(18f), dpi(18f), basePaddingBottom)
    scroll.isFillViewport = true
    scroll.addView(content, ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    scroll.isVerticalScrollBarEnabled = false
    panel.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
      topMargin = dpi(20f)
    })

    handle.setOnTouchListener { _, event -> dragHandle(event) }

    val closeButton = PdfIconButton(context)
    closeButton.iconView.icon = PdfIcon.CLOSE
    closeButton.iconView.color = PdfUi.MUTED
    closeButton.contentDescription = "Close"
    closeButton.setOnClickListener { close() }
    panel.addView(closeButton, LayoutParams(dpi(38f), dpi(38f), Gravity.TOP or Gravity.END).apply {
      topMargin = dpi(12f)
      rightMargin = dpi(10f)
    })
  }

  fun setBody(build: (LinearLayout) -> Unit) {
    content.removeAllViews()
    build(content)
  }

  /** Extra bottom padding for the on-screen keyboard. */
  fun setImeInset(bottom: Int) {
    content.setPadding(dpi(18f), dpi(18f), dpi(18f), basePaddingBottom + max(0, bottom))
  }

  /**
   * [nonModal] keeps the page live while a tool tray is open: no dimming and no
   * tap-to-dismiss scrim, so the tray can stay up while the page is tapped,
   * dragged or pinched.
   */
  fun open(nonModal: Boolean = false) {
    if (opened && visibility == VISIBLE) return
    opened = true
    modal = !nonModal
    scrim.isClickable = modal
    visibility = VISIBLE
    animator?.cancel()
    scrim.alpha = 0f
    panel.post {
      // A long comment thread must not run off the top of the screen: cap the
      // panel and let its scroll view take over.
      val maxHeight = (resources.displayMetrics.heightPixels * 0.8f).toInt()
      if (panel.height > maxHeight) {
        // Cap the panel and let the scroll view take the remaining space, so a
        // long comment thread scrolls instead of being clipped.
        val scrollHeight = (maxHeight - dpi(20f)).coerceAtLeast(dpi(120f))
        scroll.layoutParams = scroll.layoutParams.apply { height = scrollHeight }
        panel.layoutParams = panel.layoutParams.apply { height = maxHeight }
        panel.requestLayout()
      }
      val distance = panel.height.toFloat().coerceAtLeast(dpf(220f))
      panel.translationY = distance
      scrim.alpha = 0f
      animator = ValueAnimator.ofFloat(1f).apply {
        duration = 280L
        interpolator = DecelerateInterpolator(1.7f)
        addUpdateListener { animation ->
          val t = animation.animatedFraction
          panel.translationY = distance * (1f - t)
          scrim.alpha = if (modal) t else 0f
        }
        start()
      }
    }
  }

  fun close() {
    if (!opened || visibility != VISIBLE) return
    opened = false
    animator?.cancel()
    val distance = panel.translationY.coerceAtLeast(0f) + panel.height.coerceAtLeast(dpi(180f))
    animator = ValueAnimator.ofFloat(1f).apply {
      duration = 200L
      interpolator = DecelerateInterpolator()
      addUpdateListener { animation ->
        val t = animation.animatedFraction
        panel.translationY = distance * t
        scrim.alpha = if (modal) 1f - t else 0f
      }
      addListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
          if (!opened) {
            visibility = GONE
            panel.translationY = 0f
            onDismiss?.invoke()
          }
        }
      })
      start()
    }
  }

  fun isOpen(): Boolean = opened

  private fun dragHandle(event: MotionEvent): Boolean {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        animator?.cancel()
        handle.tag = event.rawY
        return true
      }
      MotionEvent.ACTION_MOVE -> {
        val start = handle.tag as? Float ?: return true
        val dy = (event.rawY - start).coerceAtLeast(0f)
        panel.translationY = dy
        if (modal) scrim.alpha = 1f - (dy / panel.height.coerceAtLeast(1)).coerceIn(0f, 1f)
        return true
      }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        if (panel.translationY > panel.height * 0.3f) close() else snapBack()
        return true
      }
    }
    return false
  }

  private fun snapBack() {
    val from = panel.translationY
    animator?.cancel()
    animator = ValueAnimator.ofFloat(1f).apply {
      duration = 180L
      interpolator = DecelerateInterpolator()
      addUpdateListener { animation ->
        val t = animation.animatedFraction
        panel.translationY = from * (1f - t)
        scrim.alpha = 0.4f + 0.6f * t
      }
      start()
    }
  }
}
