package com.margelo.nitro.hboxdocx

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Continuous, zoomable document surface.
 *
 * Every page is painted by one view instead of a RecyclerView of page views:
 * the earlier adapter sized its rows in density-independent units while the page
 * renderer scaled in pixels, so rows were `density` times too short and stacked
 * pages overlapped ("all pages crammed on one screen"). A single view also makes
 * pinch zoom, horizontal panning and the first paint after pagination
 * straightforward, because there are no recycled children to re-measure.
 *
 * `pageScale` from JS is dp per document point; everything internal is pixels.
 */
class DocxPageListView(context: Context) : View(context) {
  var onPageChange: ((Int, Int) -> Unit)? = null
  var onChromeVisibility: ((Boolean) -> Unit)? = null
  var onTextCopied: (() -> Unit)? = null
  var onLayoutProgress: ((Int, Boolean) -> Unit)? = null
  var onZoomSettled: ((Float) -> Unit)? = null

  var documentId: String = ""
    set(value) {
      field = value
      pinch = 1f
      offsetX = 0f
      scrollY = 0f
      lastReportedPage = 0
      rebuildLayout(resetScroll = true)
    }

  /** dp per document point, matching the `scale` prop resolved in JS. */
  var pageScale: Float = 1f
    set(value) {
      val next = value.coerceIn(0.05f, 12f)
      // React Native re-applies unchanged props on every update; resetting the
      // pinch on those would undo the user's zoom right after a gesture.
      if (next == field) return
      field = next
      // A new base scale supersedes any pinch offset.
      pinch = 1f
      clampScroll()
      invalidate()
      reportPage()
    }

  var mode: String = "markup"
    set(value) {
      field = DocxSession.normalizeMode(value)
      rebuildLayout(resetScroll = false)
    }

  var palette: DocxPalette? = null
    set(value) {
      field = value
      setBackgroundColor(parseDocxColor(value?.appBg, Color.LTGRAY))
      rebuildLayout(resetScroll = false)
    }

  private val density = resources.displayMetrics.density
  private val padTopPx = 12f * density
  private val padBottomPx = 28f * density
  private val gapPx = 20f * density
  private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

  private var docLayout: DocxLayout? = null
  private var scrollY = 0f
  private var offsetX = 0f
  private var pinch = 1f
  private var lastReportedPage = 0
  private var pendingPage: Int? = null
  private var pendingPageAnimated = false

  private val scroller = OverScroller(context)
  private val mainHandler = Handler(Looper.getMainLooper())
  private var layoutGeneration = 0

  /** Pagination is expensive; build it off the UI thread and coalesce bursts. */
  private val layoutExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "hbox-docx-layout").apply { isDaemon = true }
  }

  private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.FILL
  }
  private val placeholderBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = density
  }
  private val placeholderTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = 14f * density
    textAlign = Paint.Align.CENTER
  }

  /** 1px host-space outline: at fit-width the page border is sub-pixel. */
  private val pageOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 1f
    color = Color.parseColor("#d4d4d8")
  }

  private var lastTouchX = 0f
  private var lastTouchY = 0f
  private var dragging = false
  private var velocity: VelocityTracker? = null

  // Two-finger gesture: span and scale when the second finger landed.
  private var twoFinger = false
  private var gestureSpan = 0f
  private var gesturePinch = 1f
  private var gestureMidX = 0f
  private var gestureMidY = 0f

  // Text selection, as character offsets into the laid out document.
  private var selecting = false
  private var selPageIndex = -1
  private var selStart = -1
  private var selEnd = -1
  private val copyChip = RectF()

  private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = 0x2E3B82F6
    style = Paint.Style.FILL
  }
  private val selectionEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = 0xFF3B82F6.toInt()
    style = Paint.Style.STROKE
    strokeWidth = density
  }
  private val chipFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE
    style = Paint.Style.FILL
  }
  private val chipShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = 0x26000000
    style = Paint.Style.FILL
  }
  private val chipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = 0xFF1B1D22.toInt()
    textSize = 12f * density
    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    textAlign = Paint.Align.CENTER
  }

  private val gestureDetector = GestureDetector(
    context,
    object : GestureDetector.SimpleOnGestureListener() {
      override fun onLongPress(event: MotionEvent) {
        lastTouchX = event.x
        lastTouchY = event.y
        // Long press selects the word under the finger; the Copy chip then
        // copies only that range.
        if (beginSelectionAt(event.x, event.y)) return
        copyPageText(visiblePageIndex())
      }
    },
  )

  init {
    isClickable = true
    isFocusable = false
    setBackgroundColor(Color.parseColor("#f4f4f5"))
    placeholderPaint.color = Color.WHITE
    placeholderBorderPaint.color = Color.parseColor("#e4e4e7")
    placeholderTextPaint.color = Color.parseColor("#71717a")
  }

  // ---- geometry -----------------------------------------------------------

  /** Falls back to the parsed page geometry while pagination is still running. */
  private fun geometryOrNull(): DocxPageGeometry? =
    docLayout?.model?.geometry ?: DocxEngineRegistry.get(documentId)?.model?.geometry

  private fun pxPerPoint(): Float = pageScale * density * pinch

  private fun pageCount(): Int = docLayout?.pageCount ?: 0

  private fun pageHeightPx(): Float {
    val geometry = geometryOrNull() ?: return 900f
    return geometry.height * pxPerPoint()
  }

  private fun pageStride(): Float = pageHeightPx() + gapPx

  private fun pageLeft(geometry: DocxPageGeometry): Float {
    val pageWidth = geometry.width * pxPerPoint()
    return if (pageWidth <= width) (width - pageWidth) / 2f else -offsetX
  }

  private fun contentHeight(): Float {
    val count = pageCount()
    if (count <= 0) return height.toFloat()
    return padTopPx + count * pageStride() - gapPx + padBottomPx
  }

  private fun clampScroll() {
    val maxScroll = max(0f, contentHeight() - height)
    scrollY = scrollY.coerceIn(0f, maxScroll)

    val pageWidth = (geometryOrNull()?.width ?: 0f) * pxPerPoint()
    val maxOffset = max(0f, pageWidth - width)
    offsetX = offsetX.coerceIn(0f, maxOffset)
  }

  private fun visiblePageIndex(): Int {
    val stride = pageStride()
    if (stride <= 0f) return 0
    val index = ((scrollY - padTopPx) / stride).roundToInt().coerceIn(0, max(0, pageCount() - 1))
    return index
  }

  private fun reportPage() {
    val count = pageCount()
    if (count <= 0) return
    val page = visiblePageIndex() + 1
    if (page == lastReportedPage) return
    lastReportedPage = page
    onPageChange?.invoke(page, count)
  }

  // ---- pagination ---------------------------------------------------------

  private fun rebuildLayout(resetScroll: Boolean) {
    val session = DocxEngineRegistry.get(documentId)
    val generation = ++layoutGeneration

    if (session == null) {
      docLayout = null
      invalidate()
      return
    }

    val requestedMode = mode
    val darkMode = palette?.pageDarkMode == true

    layoutExecutor.execute {
      val built = try {
        session.layout(requestedMode, darkMode)
      } catch (error: Throwable) {
        // A document that cannot paginate shows the placeholder instead of
        // taking the whole view down.
        null
      }
      mainHandler.post {
        if (generation != layoutGeneration) return@post
        docLayout = built
        if (resetScroll) {
          scrollY = 0f
          offsetX = 0f
        }
        pendingPage?.let { page ->
          pendingPage = null
          scrollToPage(page, pendingPageAnimated)
        }
        clampScroll()
        invalidate()
        onLayoutProgress?.invoke(built?.pageCount ?: 0, true)
        reportPage()
      }
    }
  }

  fun refresh() {
    clampScroll()
    invalidate()
  }

  fun scrollToPage(pageIndex: Int, animated: Boolean) {
    val count = pageCount()
    if (count <= 0) {
      // JS can ask to jump before the first pagination finishes; honour it then.
      pendingPage = pageIndex
      pendingPageAnimated = animated
      return
    }
    val target = padTopPx + pageIndex.coerceIn(0, count - 1) * pageStride()
    if (animated) {
      scroller.startScroll(0, scrollY.roundToInt(), 0, (target - scrollY).roundToInt(), 280)
      postInvalidateOnAnimation()
    } else {
      scrollY = target
      clampScroll()
      invalidate()
      reportPage()
    }
  }

  // ---- scrolling ----------------------------------------------------------

  private fun scrollByPixels(dx: Float, dy: Float) {
    if (dy != 0f) scrollY += dy
    if (dx != 0f) offsetX += dx
    clampScroll()
    invalidate()
    reportPage()
  }

  private fun applyPinch(next: Float, focusX: Float, focusY: Float) {
    val oldPx = pxPerPoint()
    if (oldPx <= 0f) {
      pinch = next
      invalidate()
      return
    }
    // Keep the document point under the fingers anchored while scaling.
    val focusContentX = (offsetX + focusX) / oldPx
    val focusContentY = (scrollY + focusY - padTopPx) / oldPx

    pinch = next
    val newPx = pxPerPoint()
    offsetX = focusContentX * newPx - focusX
    scrollY = focusContentY * newPx + padTopPx - focusY
    clampScroll()
    invalidate()
    reportPage()
  }

  override fun computeScroll() {
    if (scroller.computeScrollOffset()) {
      scrollY = scroller.currY.toFloat()
      offsetX = scroller.currX.toFloat()
      clampScroll()
      invalidate()
      reportPage()
      postInvalidateOnAnimation()
    }
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    if (event.pointerCount >= 2) {
      handleTwoFinger(event)
      return true
    }
    if (twoFinger) {
      // Pinch finished; a finger still down only pans from here.
      twoFinger = false
      onZoomSettled?.invoke(pinch)
      velocity?.recycle()
      velocity = null
      if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
        dragging = false
        return true
      }
      lastTouchX = event.x
      lastTouchY = event.y
      dragging = true
    }
    gestureDetector.onTouchEvent(event)

    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        if (selecting && copyChip.contains(event.x, event.y)) {
          copySelection()
          return true
        }
        if (selecting) clearSelection()
        scroller.forceFinished(true)
        lastTouchX = event.x
        lastTouchY = event.y
        dragging = false
        velocity?.recycle()
        velocity = VelocityTracker.obtain().also { it.addMovement(event) }
      }

      MotionEvent.ACTION_MOVE -> {
        velocity?.addMovement(event)
        if (selecting) {
          // Dragging with a selection already started grows it instead of
          // scrolling the page.
          extendSelection(event.x, event.y)
          lastTouchX = event.x
          lastTouchY = event.y
          return true
        }
        val dx = lastTouchX - event.x
        val dy = lastTouchY - event.y
        if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
        if (dragging) {
          scrollByPixels(dx, dy)
          lastTouchX = event.x
          lastTouchY = event.y
          parent?.requestDisallowInterceptTouchEvent(true)
        }
      }

      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        val tracker = velocity
        if (tracker != null && !selecting) {
          tracker.computeCurrentVelocity(1000)
          val yVelocity = tracker.yVelocity
          if (abs(yVelocity) > 120f) {
            val maxScroll = max(0f, contentHeight() - height).roundToInt()
            scroller.fling(0, scrollY.roundToInt(), 0, (-yVelocity).roundToInt(), 0, 0, 0, maxScroll)
            postInvalidateOnAnimation()
          }
          tracker.recycle()
        }
        velocity = null
        dragging = false
      }
    }
    return true
  }

  /**
   * Two fingers navigate: the span between them scales around the midpoint and
   * the midpoint's own movement pans. Raw pointer positions rather than a
   * ScaleGestureDetector, so the pinch and the pan share one handler.
   */
  private fun handleTwoFinger(event: MotionEvent) {
    val span = spanAt(event)
    val midX = midAt(event, horizontal = true)
    val midY = midAt(event, horizontal = false)
    if (!twoFinger) {
      twoFinger = true
      clearSelection()
      scroller.forceFinished(true)
      dragging = false
      velocity?.recycle()
      velocity = null
      gestureSpan = span
      gesturePinch = pinch
      gestureMidX = midX
      gestureMidY = midY
      return
    }
    when (event.actionMasked) {
      MotionEvent.ACTION_MOVE -> {
        val minSpan = 24f * density
        if (gestureSpan < minSpan) {
          // Fingers landed almost on top of each other: start measuring once
          // they are far enough apart to give a stable ratio.
          gestureSpan = span
          gesturePinch = pinch
        } else if (span > minSpan) {
          val next = (gesturePinch * (span / gestureSpan)).coerceIn(MIN_PINCH, MAX_PINCH)
          applyPinch(next, midX, midY)
        }
        scrollByPixels(gestureMidX - midX, gestureMidY - midY)
      }
      MotionEvent.ACTION_POINTER_UP -> {
        gestureSpan = span
        gesturePinch = pinch
      }
      else -> Unit
    }
    gestureMidX = midX
    gestureMidY = midY
  }

  private fun spanAt(event: MotionEvent): Float {
    val count = event.pointerCount
    if (count < 2) return 0f
    var total = 0f
    var pairs = 0
    for (i in 0 until count - 1) {
      for (j in i + 1 until count) {
        total += hypot(event.getX(j) - event.getX(i), event.getY(j) - event.getY(i))
        pairs++
      }
    }
    return if (pairs == 0) 0f else total / pairs
  }

  private fun midAt(event: MotionEvent, horizontal: Boolean): Float {
    val count = event.pointerCount.coerceAtLeast(1)
    var total = 0f
    for (i in 0 until count) total += if (horizontal) event.getX(i) else event.getY(i)
    return total / count
  }

  // ---- text selection ------------------------------------------------------

  /** Point in page space for a view coordinate, plus which page it landed on. */
  private fun pagePointAt(x: Float, y: Float): Triple<Int, Float, Float>? {
    val layout = docLayout ?: return null
    val count = pageCount()
    val scale = pxPerPoint()
    if (count <= 0 || scale <= 0f || y < padTopPx) return null
    val stride = pageStride()
    if (stride <= 0f) return null
    val index = ((y - padTopPx) / stride).toInt().coerceIn(0, count - 1)
    val top = padTopPx + index * stride - scrollY
    val geometry = layout.model.geometry
    val pageX = (x - pageLeft(geometry)) / scale
    val pageY = (y - top) / scale
    if (pageY < 0f || pageY > geometry.height) return null
    return Triple(index, pageX, pageY)
  }

  private fun beginSelectionAt(x: Float, y: Float): Boolean {
    val layout = docLayout ?: return false
    val hit = pagePointAt(x, y) ?: return false
    val offset = layout.offsetForPoint(hit.first, hit.second, hit.third)
    if (offset < 0) return false
    val range = layout.wordRangeAt(offset)
    if (range.second <= range.first) return false
    selecting = true
    selPageIndex = hit.first
    selStart = range.first
    selEnd = range.second
    invalidate()
    return true
  }

  private fun extendSelection(x: Float, y: Float) {
    val layout = docLayout ?: return
    val hit = pagePointAt(x, y) ?: return
    val offset = layout.offsetForPoint(hit.first, hit.second, hit.third)
    if (offset < 0) return
    selEnd = offset
    invalidate()
  }

  private fun clearSelection() {
    if (!selecting) return
    selecting = false
    selPageIndex = -1
    selStart = -1
    selEnd = -1
    copyChip.setEmpty()
    invalidate()
  }

  private fun copySelection() {
    val layout = docLayout ?: return
    val text = layout.textBetween(selStart, selEnd).trim()
    if (text.isNotEmpty()) {
      val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
      clipboard?.setPrimaryClip(ClipData.newPlainText("Selection", text))
      onTextCopied?.invoke()
    }
    clearSelection()
  }

  private fun copyPageText(pageIndex: Int) {
    val layout = docLayout ?: return
    val text = layout.pageText(pageIndex)
    if (text.isBlank()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("Page ${pageIndex + 1}", text))
    onTextCopied?.invoke()
  }

  // ---- drawing ------------------------------------------------------------

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val theme = palette ?: return
    val geometry = geometryOrNull() ?: return
    val scale = pxPerPoint()
    val pageWidth = geometry.width * scale
    val pageHeight = geometry.height * scale
    if (pageWidth <= 1f || pageHeight <= 1f) return

    val count = pageCount()
    if (count <= 0) {
      drawPlaceholder(canvas, geometry, pageWidth, pageHeight, theme)
      return
    }

    val stride = pageHeight + gapPx
    val first = max(0, (scrollY / stride).toInt())
    val last = min(count - 1, ((scrollY + height) / stride).toInt())
    val left = if (pageWidth <= width) (width - pageWidth) / 2f else -offsetX
    val current = docLayout

    for (index in first..last) {
      val top = padTopPx + index * stride - scrollY
      val checkpoint = canvas.save()
      canvas.translate(left, top)
      try {
        if (current != null) {
          canvas.scale(scale, scale)
          current.drawPage(canvas, index, theme)
        } else {
          drawPagePlaceholder(canvas, geometry, scale, theme)
        }
      } catch (error: Throwable) {
        // A malformed paragraph draws a blank page instead of aborting the
        // frame for every other page.
        canvas.drawRect(0f, 0f, pageWidth, pageHeight, placeholderPaint)
      }
      canvas.restoreToCount(checkpoint)

      // The page edge is invisible against the near-white surface when the page
      // is exactly as wide as the screen (fit-width). Draw it in host pixels so
      // stacked pages always read as separate sheets.
      pageOutlinePaint.color = parseDocxColor(theme.pageBorder, Color.LTGRAY)
      canvas.drawRect(left, top, left + pageWidth, top + pageHeight, pageOutlinePaint)
    }

    if (current == null) drawPreparingLabel(canvas)
    if (selecting) drawSelection(canvas)
  }

  /** Selected lines plus the Copy chip that copies just that range. */
  private fun drawSelection(canvas: Canvas) {
    val layout = docLayout ?: return
    if (selPageIndex < 0) return
    val rects = layout.selectionRects(selPageIndex, selStart, selEnd)
    if (rects.isEmpty()) {
      copyChip.setEmpty()
      return
    }
    val geometry = layout.model.geometry
    val scale = pxPerPoint()
    if (scale <= 0f) return
    val left = pageLeft(geometry)
    val top = padTopPx + selPageIndex * pageStride() - scrollY
    var minTop = Float.MAX_VALUE
    var maxRight = -Float.MAX_VALUE
    rects.forEach { box ->
      val l = left + box.left * scale
      val t = top + box.top * scale
      val r = left + box.right * scale
      val b = top + box.bottom * scale
      canvas.drawRect(l, t, r, b, selectionPaint)
      canvas.drawRect(l, t, r, b, selectionEdgePaint)
      if (t < minTop) minTop = t
      if (r > maxRight) maxRight = r
    }

    val halfWidth = 36f * density
    val halfHeight = 17f * density
    val cx = min(width - halfWidth - 6f * density, maxRight).coerceAtLeast(halfWidth + 6f * density)
    val cy = (minTop - halfHeight - 10f * density).coerceAtLeast(halfHeight + 4f * density)
    copyChip.set(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight)
    canvas.drawRoundRect(
      copyChip.left,
      copyChip.top + 2f * density,
      copyChip.right,
      copyChip.bottom + 2f * density,
      halfHeight,
      halfHeight,
      chipShadowPaint,
    )
    canvas.drawRoundRect(copyChip, halfHeight, halfHeight, chipFillPaint)
    canvas.drawRoundRect(copyChip, halfHeight, halfHeight, selectionEdgePaint)
    canvas.drawText(
      "Copy",
      cx,
      cy - (chipTextPaint.ascent() + chipTextPaint.descent()) / 2f,
      chipTextPaint,
    )
  }

  private fun drawPlaceholder(
    canvas: Canvas,
    geometry: DocxPageGeometry,
    pageWidth: Float,
    pageHeight: Float,
    theme: DocxPalette,
  ) {
    // The document is parsed but not paginated yet: show the first page as a
    // skeleton so the viewer never looks blank while layout is running.
    val left = if (pageWidth <= width) (width - pageWidth) / 2f else -offsetX
    val checkpoint = canvas.save()
    canvas.translate(left, padTopPx)
    canvas.scale(pxPerPoint(), pxPerPoint())
    drawPagePlaceholder(canvas, geometry, pxPerPoint(), theme)
    canvas.restoreToCount(checkpoint)
    drawPreparingLabel(canvas)
  }

  private fun drawPagePlaceholder(
    canvas: Canvas,
    geometry: DocxPageGeometry,
    scale: Float,
    theme: DocxPalette,
  ) {
    placeholderPaint.color = parseDocxColor(theme.pageBg, Color.WHITE)
    placeholderBorderPaint.color = parseDocxColor(theme.pageBorder, Color.LTGRAY)
    canvas.drawRect(0f, 0f, geometry.width, geometry.height, placeholderPaint)
    canvas.drawRect(0.5f, 0.5f, geometry.width - 0.5f, geometry.height - 0.5f, placeholderBorderPaint)
    if (scale > 0f) {
      placeholderTextPaint.textSize = 16f / scale
      placeholderTextPaint.color = parseDocxColor(theme.muted, Color.GRAY)
      canvas.drawText(
        "Preparing document…",
        geometry.width / 2f,
        geometry.height / 2f,
        placeholderTextPaint,
      )
    }
  }

  private fun drawPreparingLabel(canvas: Canvas) {
    placeholderTextPaint.textSize = 12f * density
    placeholderTextPaint.color = parseDocxColor(palette?.muted, Color.GRAY)
    canvas.drawText(
      "Preparing document…",
      width / 2f,
      (height - padBottomPx / 2f).coerceAtLeast(24f * density),
      placeholderTextPaint,
    )
  }

  private companion object {
    const val MIN_PINCH = 0.4f
    const val MAX_PINCH = 6f
  }
}
