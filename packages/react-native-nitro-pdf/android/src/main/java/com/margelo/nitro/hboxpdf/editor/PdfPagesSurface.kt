package com.margelo.nitro.hboxpdf.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.SparseArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.margelo.nitro.hboxpdf.PdfiumLock
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfiumCore
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal enum class PdfTool { NONE, HIGHLIGHT, DRAW, COMMENT, SIGN }

/**
 * Continuous, zoomable PDF surface that also paints every edit.
 *
 * One view owns the whole document: pages are rasterised on demand into a small
 * LRU of bitmaps, marks are stored normalised to the page box and drawn over the
 * page, and gestures are resolved against those same normalised coordinates.
 * That is what lets a comment pin be dragged and a signature be moved without
 * re-rendering anything or round-tripping through React Native.
 */
internal class PdfPagesSurface(context: Context) : View(context) {
  var onPageChanged: ((Int, Int) -> Unit)? = null
  var onMarksChanged: (() -> Unit)? = null
  var onCommentAt: ((Int, Float, Float) -> Unit)? = null
  var onNoteTapped: ((PdfMark) -> Unit)? = null
  var onHint: ((String) -> Unit)? = null
  var onZoomChanged: ((Int) -> Unit)? = null
  var onDocumentReady: ((Int) -> Unit)? = null
  var onSelectionChanged: ((PdfMark?) -> Unit)? = null
  var onOpenFailed: (() -> Unit)? = null

  var tool: PdfTool = PdfTool.NONE
    set(value) {
      field = value
      if (value != PdfTool.NONE) select(null)
      invalidate()
    }

  var highlightColor: Int = PdfUi.HIGHLIGHTS[0]
  var inkColor: Int = PdfUi.INKS[0]
  /** Markup style applied to a text selection: highlight, underline or strikeout. */
  var highlightStyle: String = "highlight"
  /** Alpha of a highlight, 0.05..1.0; underlines and strikeouts always print solid. */
  var highlightOpacity: Float = 0.4f
  /** 0..2: thin, regular, thick. */
  var strokeStep: Int = 1
  /** Pen opacity, 0.05..1.0. */
  var inkOpacity: Float = 1f
  /** Signature or stamp waiting for the next tap to be placed. */
  var pendingSignature: PdfMark? = null

  var marks: MutableList<PdfMark> = mutableListOf()
    set(value) {
      field = value
      invalidate()
    }

  private val density = resources.displayMetrics.density
  private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
  private val gapPx = 14f * density
  private val padTopPx = 10f * density
  private val padBottomPx = 26f * density

  private var core: PdfiumCore? = null
  private var document: PdfDocument? = null
  private var descriptor: ParcelFileDescriptor? = null
  private var documentPath: String = ""
  private var openGeneration = 0
  private var pageSizes: SparseArray<PointF> = SparseArray()
  private var pageTops = FloatArray(0)
  private var pageCount = 0
  private var failed = false
  private var baseScale = 1f
  private var contentHeight = 0f
  private var initialPage = 1
  private var pendingInitialPage = 1

  private var scrollY = 0f
  private var offsetX = 0f
  private var pinch = 1f
  private var lastReportedPage = 0

  private var downX = 0f
  private var downY = 0f
  private var moved = false
  private var velocity: VelocityTracker? = null
  private val scroller = OverScroller(context)
  private val mainHandler = Handler(Looper.getMainLooper())

  private var activeStroke: PdfMark? = null
  /** Last finger position while a highlight is being dragged, for the loupe. */
  private var loupeX = 0f
  private var loupeY = 0f
  /** Text selection: char boxes for the page, and the dragged character range. */
  private val textBoxes = object : android.util.LruCache<Int, List<RectF>>(6) {}
  /** The same char boxes packed as [l, t, r, b] for the pure geometry code. */
  private val boxArrays = SparseArray<FloatArray>()
  private val textLoading = HashSet<Int>()
  /** Marquee highlight for pages that have no text layer (scanned PDFs). */
  private var marqueeActive = false
  private var marqueePage = -1
  private var marqueeStartX = 0f
  private var marqueeStartY = 0f
  private var marqueeEndX = 0f
  private var marqueeEndY = 0f
  /**
   * Page rotation (PDFium code 0..3) and crop box, captured while measuring the
   * document. Both are needed to place text on the rendered page.
   */
  private var pageRotations = SparseArray<Int>()
  private var pageCrops = SparseArray<RectF>()
  /**
   * PDFium reports text in page space while the renderer draws the page through
   * its /Rotate and CropBox. The first rotated page with real text decides which
   * space we are in, and the answer holds for the whole document.
   */
  private var selPage = -1
  private var selAnchor = -1
  private var selFocus = -1
  private var selecting = false
  /** Mark the kept selection belongs to, so handles can resize it in place. */
  private var appliedMarkId: String? = null
  private var handleDrag = 0
  private var dragHistory = false
  /** A touch that arrived before the page's char boxes finished loading. */
  private var pendingSelect: Pair<Int, PointF>? = null
  private var restylePending = false
  private var suppressNextUp = false
  /** Pinch state: span and scale when the second finger landed. */
  private var twoFinger = false
  private var gestureSpan = 0f
  private var gesturePinch = 1f
  private var gestureMidX = 0f
  private var gestureMidY = 0f
  private var lastTapAt = 0L
  private var lastTapX = 0f
  private var lastTapY = 0f
  private var tapCount = 0
  private val undoStack = ArrayDeque<String>()
  private val redoStack = ArrayDeque<String>()
  private var dragMark: PdfMark? = null
  private var dragStartRect = RectF()
  private var dragFromX = 0f
  private var dragFromY = 0f

  private var selectedId: String? = null
  private val chipRect = RectF()
  private val loupeFill = Paint(Paint.ANTI_ALIAS_FLAG)
  private val loupeBitmap = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
  private val loupeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
  }
  private val loupeClip = Path()

  private val renderExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "hbox-pdf-editor-render").apply { isDaemon = true }
  }
  private val pendingRenders = HashSet<Long>()
  private val bitmaps = object : LinkedHashMap<Long, Bitmap>(6, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>): Boolean = size > 4
  }

  private val pagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
  private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 1f
    color = 0xFFDFE1E6.toInt()
  }
  private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
  private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
  }
  private val stampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textAlign = Paint.Align.CENTER
    typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
  }
  private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val pinGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    color = Color.WHITE
  }
  private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 1.4f * density
    color = PdfUi.ACCENT
    pathEffect = android.graphics.DashPathEffect(floatArrayOf(7f * density, 5f * density), 0f)
  }
  private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textAlign = Paint.Align.CENTER
    textSize = 13f * density
    color = 0xFF9AA0AA.toInt()
    typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
  }

  init {
    isClickable = true
    isFocusable = false
    setBackgroundColor(PdfUi.CANVAS)
  }

  // ---- document -----------------------------------------------------------

  fun open(path: String, startPage: Int) {
    if (path == documentPath && document != null) return
    val target = if (path.startsWith("file://")) path.substring("file://".length) else path
    documentPath = path
    failed = false
    invalidate()
    initialPage = startPage.coerceAtLeast(1)
    pendingInitialPage = initialPage
    closeDocument()
    val generation = ++openGeneration
    renderExecutor.execute {
      val opened = try {
        openUnderLock(target)
      } catch (error: Throwable) {
        null
      }
      mainHandler.post {
        if (generation != openGeneration) return@post
        applyDocument(opened?.first, opened?.second, opened?.third)
      }
    }
  }

  private fun openUnderLock(path: String): Triple<PdfDocument, PdfiumCore, ParcelFileDescriptor>? {
    return try {
      PdfiumLock.withLock {
        val file = File(path)
        if (!file.exists()) return@withLock null
        val opened = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val pdfium = PdfiumCore()
        val doc = try {
          pdfium.newDocument(opened, null)
        } catch (error: Throwable) {
          opened.close()
          throw error
        }
        if (doc.getPageCount() <= 0) {
          doc.close()
          opened.close()
          return@withLock null
        }
        Triple(doc, pdfium, opened)
      }
    } catch (error: Throwable) {
      null
    }
  }

  private fun applyDocument(doc: PdfDocument?, pdfium: PdfiumCore?, fd: ParcelFileDescriptor?) {
    pageSizes = SparseArray()
    pageRotations = SparseArray()
    pageCrops = SparseArray()
    bitmaps.clear()
    // Char boxes belong to the previous document; keeping them would place a
    // new document's selection on the old one's glyphs.
    textBoxes.evictAll()
    boxArrays.clear()
    textLoading.clear()
    pendingSelect = null
    marqueeActive = false
    marqueePage = -1
    if (doc == null) {
      pageCount = 0
      failed = true
      invalidate()
      onOpenFailed?.invoke()
      return
    }
    failed = false
    core = pdfium
    document = doc
    descriptor = fd
    pageCount = doc.getPageCount()
    var widest = 0f
    var index = 0
    while (index < pageCount) {
      try {
        val page = doc.openPage(index)
        if (page != null) {
          val width = page.getPageWidthPoint().toFloat().takeIf { it > 0f } ?: 612f
          val height = page.getPageHeightPoint().toFloat().takeIf { it > 0f } ?: 792f
          pageSizes.put(index, PointF(width, height))
          pageRotations.put(index, try {
            page.getPageRotation().coerceIn(0, 3)
          } catch (_: Throwable) {
            0
          })
          pageCrops.put(index, try {
            RectF(page.getPageCropBox())
          } catch (_: Throwable) {
            RectF(0f, 0f, width, height)
          })
          widest = max(widest, width)
          page.close()
        }
      } catch (_: Throwable) {
        // A page that cannot be measured keeps the A4 fallback.
      }
      index++
    }
    if (widest <= 0f) widest = 612f
    layoutPages(widest)
    if (pendingInitialPage > 1) scrollToPage(pendingInitialPage, false)
    requestVisibleRenders()
    onDocumentReady?.invoke(pageCount)
    invalidate()
  }

  private fun closeDocument() {
    val doc = document ?: return
    document = null
    bitmaps.clear()
    pendingRenders.clear()
    try {
      PdfiumLock.withLock {
        doc.close()
        descriptor?.close()
      }
    } catch (_: Throwable) {
      // Already closed by another surface.
    }
    descriptor = null
    core = null
    pageCount = 0
  }

  fun release() {
    openGeneration++
    closeDocument()
  }

  /** Back to the fit-width scale, with the current page kept on screen. */
  fun fitWidth() {
    pinch = 1f
    offsetX = 0f
    var y = padTopPx
    for (index in 0 until pageCount) {
      pageTops[index] = y
      y += pageHeightPx(index) + gapPx
    }
    contentHeight = y - gapPx + padBottomPx
    clampScroll()
    onZoomChanged?.invoke(100)
    requestVisibleRenders()
    invalidate()
  }

  /** Fits the whole current page inside the viewport, centred. */
  fun fitPage() {
    val index = visiblePage()
    val size = pageSize(index)
    if (size.x <= 0f || size.y <= 0f || baseScale <= 0f) return
    val pointScale = min(
      (width.toFloat() / size.x).coerceAtLeast(0.01f),
      (height.toFloat() / size.y).coerceAtLeast(0.01f),
    )
    pinch = (pointScale / baseScale).coerceIn(MIN_PINCH, MAX_PINCH)
    offsetX = 0f
    var y = padTopPx
    for (i in 0 until pageCount) {
      pageTops[i] = y
      y += pageHeightPx(i) + gapPx
    }
    contentHeight = y - gapPx + padBottomPx
    scrollY = pageTops[index]
    clampScroll()
    onZoomChanged?.invoke((pinch * 100f).roundToInt())
    requestVisibleRenders()
    invalidate()
  }

  /**
   * Text search over the PDF text layer, reported back on the main thread.
   *
   * Only the page numbers are returned: the editor jumps to the page, which is
   * what the native sheet offers, and avoids depending on PDFium's rect API.
   */
  /** Warms the text layer for the visible pages before the first drag. */
  fun prepareMarkup() {
    requestVisibleRenders()
  }

  fun searchText(query: String, onResult: (List<Int>) -> Unit) {
    val doc = document
    if (doc == null || query.isBlank()) {
      onResult(emptyList())
      return
    }
    val generation = openGeneration
    renderExecutor.execute {
      val hits = mutableListOf<Int>()
      try {
        PdfiumLock.withLock {
          for (index in 0 until pageCount) {
            val page = doc.openPage(index) ?: continue
            try {
              val textPage = page.openTextPage()
              try {
                val characters = textPage.textPageCountChars()
                if (characters > 0) {
                  val text = textPage.textPageGetText(0, characters) ?: ""
                  if (text.contains(query, ignoreCase = true)) hits.add(index + 1)
                }
              } finally {
                textPage.close()
              }
            } catch (_: Throwable) {
              // A page without a text layer is simply not a match.
            } finally {
              try {
                page.close()
              } catch (_: Throwable) {
              }
            }
          }
        }
      } catch (_: Throwable) {
      }
      mainHandler.post {
        if (generation == openGeneration) onResult(hits)
      }
    }
  }

  private fun pageSizeAt(index: Int): PointF = pageSize(index)

  // ---- geometry -----------------------------------------------------------

  private fun pageSize(index: Int): PointF = pageSizes.get(index) ?: PointF(612f, 792f)

  private fun layoutPages(widest: Float) {
    val target = if (width > 0) width.toFloat() else resources.displayMetrics.widthPixels.toFloat()
    baseScale = (target / widest.coerceAtLeast(1f))
    pageTops = FloatArray(pageCount)
    var y = padTopPx
    for (index in 0 until pageCount) {
      pageTops[index] = y
      y += pageSize(index).y * pxPerPoint() + gapPx
    }
    contentHeight = y - gapPx + padBottomPx
    clampScroll()
  }

  private fun pxPerPoint(): Float = baseScale * pinch

  private fun pageWidthPx(index: Int): Float = pageSize(index).x * pxPerPoint()

  private fun pageHeightPx(index: Int): Float = pageSize(index).y * pxPerPoint()

  private fun pageLeft(index: Int): Float {
    val pageWidth = pageWidthPx(index)
    return if (pageWidth <= width) (width - pageWidth) / 2f else -offsetX
  }

  private fun clampScroll() {
    val maxScroll = max(0f, contentHeight - height)
    scrollY = scrollY.coerceIn(0f, maxScroll)
    val widestPage = (0 until pageCount).maxOfOrNull { pageWidthPx(it) } ?: 0f
    val maxOffset = max(0f, widestPage - width)
    offsetX = offsetX.coerceIn(0f, maxOffset)
  }

  private fun pageAt(y: Float): Int {
    if (pageCount <= 0) return 0
    var index = 0
    for (i in 0 until pageCount) {
      val top = pageTops[i] - scrollY
      if (y >= top && y <= top + pageHeightPx(i)) return i
      if (y > top) index = i
    }
    return index.coerceIn(0, pageCount - 1)
  }

  private fun visiblePage(): Int {
    if (pageCount <= 0) return 0
    val anchor = scrollY + height * 0.35f
    var index = 0
    for (i in 0 until pageCount) {
      if (pageTops[i] <= anchor) index = i
    }
    return index
  }

  private fun reportPage() {
    val page = visiblePage() + 1
    if (page == lastReportedPage) return
    lastReportedPage = page
    onPageChanged?.invoke(page, pageCount)
  }

  private fun toPage(index: Int, x: Float, y: Float): PointF {
    val left = pageLeft(index)
    val top = pageTops[index] - scrollY
    val w = pageWidthPx(index).coerceAtLeast(1f)
    val h = pageHeightPx(index).coerceAtLeast(1f)
    return PointF(((x - left) / w).coerceIn(0f, 1f), ((y - top) / h).coerceIn(0f, 1f))
  }

  fun scrollToPage(page: Int, animated: Boolean) {
    if (pageCount <= 0) {
      pendingInitialPage = page
      return
    }
    val index = (page - 1).coerceIn(0, pageCount - 1)
    val target = pageTops[index]
    if (animated) {
      scroller.startScroll(0, scrollY.roundToInt(), 0, (target - scrollY).roundToInt(), 260)
      postInvalidateOnAnimation()
    } else {
      scrollY = target
      clampScroll()
      invalidate()
      reportPage()
    }
    // The pages that just became visible have no bitmap yet: without this a
    // jump (search result, page field) lands on a blank sheet.
    requestVisibleRenders()
  }

  // ---- rendering ----------------------------------------------------------

  private fun renderKey(index: Int, targetWidth: Int): Long =
    (index.toLong() shl 32) or targetWidth.toLong()

  private fun targetWidth(index: Int): Int {
    val widthPx = pageWidthPx(index)
    val heightPx = pageHeightPx(index)
    if (widthPx <= 0f || heightPx <= 0f) return 1
    // Keep a single page under ~4M pixels so several can live in the heap.
    val area = widthPx * heightPx
    val factor = if (area > MAX_PAGE_PIXELS) kotlin.math.sqrt(MAX_PAGE_PIXELS / area) else 1f
    return (widthPx * factor).roundToInt().coerceAtLeast(1)
  }

  private fun requestVisibleRenders() {
    if (pageCount <= 0) return
    for (index in 0 until pageCount) {
      val top = pageTops[index] - scrollY
      val bottom = top + pageHeightPx(index)
      if (bottom < -height * 0.5f || top > height * 1.5f) continue
      // Prefetch the text layer too: selection must be instant on the page the
      // reader can see.
      ensureTextBoxes(index)
      val target = targetWidth(index)
      val key = renderKey(index, target)
      if (bitmaps.containsKey(key) || pendingRenders.contains(key)) continue
      pendingRenders.add(key)
      val doc = document ?: return
      val generation = openGeneration
      renderExecutor.execute {
        val bitmap = try {
          renderPage(doc, index, target, targetWidthHeight(index, target), generation)
        } catch (_: Throwable) {
          null
        }
        mainHandler.post {
          pendingRenders.remove(key)
          if (generation != openGeneration) return@post
          if (bitmap != null) {
            bitmaps[key] = bitmap
            invalidate()
          }
        }
      }
    }
  }

  private fun targetWidthHeight(index: Int, targetWidth: Int): Int {
    val size = pageSize(index)
    return (targetWidth * (size.y / size.x)).roundToInt().coerceAtLeast(1)
  }

  private fun renderPage(
    doc: PdfDocument,
    index: Int,
    targetWidth: Int,
    targetHeight: Int,
    generation: Int,
  ): Bitmap? {
    return PdfiumLock.withLock {
      // The document may have been released between queueing and running.
      if (generation != openGeneration) return@withLock null
      val page = doc.openPage(index) ?: return@withLock null
      try {
        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        page.renderPageBitmap(bitmap, 0, 0, targetWidth, targetHeight, true)
        bitmap
      } finally {
        page.close()
      }
    }
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    if (w > 0) {
      val widest = (0 until pageCount).maxOfOrNull { pageSize(it).x } ?: 612f
      layoutPages(widest)
      bitmaps.clear()
      pendingRenders.clear()
      requestVisibleRenders()
    }
  }

  // ---- scrolling ----------------------------------------------------------

  override fun computeScroll() {
    if (scroller.computeScrollOffset()) {
      scrollY = scroller.currY.toFloat()
      clampScroll()
      invalidate()
      reportPage()
      requestVisibleRenders()
      postInvalidateOnAnimation()
    }
  }

  private fun applyPinch(next: Float, focusX: Float, focusY: Float) {
    val oldPx = pxPerPoint()
    if (oldPx <= 0f) {
      pinch = next
      return
    }
    val focusContentX = (offsetX + focusX) / oldPx
    val focusContentY = (scrollY + focusY - padTopPx) / oldPx
    pinch = next
    val newPx = pxPerPoint()
    offsetX = focusContentX * newPx - focusX
    scrollY = focusContentY * newPx + padTopPx - focusY
    // Page heights changed; keep the offsets in step with the new scale.
    var y = padTopPx
    for (index in 0 until pageCount) {
      pageTops[index] = y
      y += pageHeightPx(index) + gapPx
    }
    contentHeight = y - gapPx + padBottomPx
    clampScroll()
    invalidate()
    reportPage()
  }

  // ---- touch --------------------------------------------------------------

  override fun onTouchEvent(event: MotionEvent): Boolean {
    if (event.pointerCount >= 2) {
      handleTwoFinger(event)
      return true
    }
    if (twoFinger) {
      // The pinch is over. A finger that is still down only pans from here, and
      // the last lift must not be read as a tap.
      twoFinger = false
      onZoomChanged?.invoke((pinch * 100f).roundToInt())
      requestVisibleRenders()
      resetVelocity()
      if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
        suppressNextUp = false
        return true
      }
      downX = event.x
      downY = event.y
      moved = true
    }
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> handleDown(event)
      MotionEvent.ACTION_MOVE -> handleMove(event)
      MotionEvent.ACTION_UP -> handleUp(event)
      MotionEvent.ACTION_CANCEL -> resetGesture()
    }
    return true
  }

  /**
   * Two fingers always navigate: the distance between them scales the page
   * around their midpoint and the midpoint's own movement pans, in the same
   * gesture. Whatever the armed tool was doing is dropped.
   *
   * This reads the raw pointer positions instead of delegating to a
   * ScaleGestureDetector, so one handler owns both parts of the gesture and
   * neither can starve the other.
   */
  private fun handleTwoFinger(event: MotionEvent) {
    val span = spanAt(event)
    val midX = midAt(event, horizontal = true)
    val midY = midAt(event, horizontal = false)
    if (!twoFinger) {
      if (selecting || activeStroke != null || dragMark != null || handleDrag != 0) {
        cancelActiveEdit()
      }
      twoFinger = true
      suppressNextUp = true
      scroller.forceFinished(true)
      resetVelocity()
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
        scrollY += gestureMidY - midY
        offsetX += gestureMidX - midX
        clampScroll()
        invalidate()
        reportPage()
        requestVisibleRenders()
      }
      // One finger of three lifted: rebase so the scale does not jump.
      MotionEvent.ACTION_POINTER_UP -> {
        gestureSpan = span
        gesturePinch = pinch
      }
      else -> Unit
    }
    gestureMidX = midX
    gestureMidY = midY
  }

  /** Mean distance between every pair of fingers, in view pixels. */
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

  /**
   * A second finger cancels what the tool was doing. A stroke or range is
   * dropped; a mark that was already dragged keeps the position it reached,
   * because the user saw it move.
   */
  private fun cancelActiveEdit() {
    if (dragMark != null && moved) onMarksChanged?.invoke()
    activeStroke = null
    dragMark = null
    dragHistory = false
    handleDrag = 0
    if (selecting) {
      selecting = false
      selPage = -1
      selAnchor = -1
      selFocus = -1
    }
    appliedMarkId = null
    invalidate()
  }

  private fun handleDown(event: MotionEvent) {
    suppressNextUp = false
    scroller.forceFinished(true)
    downX = event.x
    downY = event.y
    moved = false
    velocity?.recycle()
    velocity = VelocityTracker.obtain().also { it.addMovement(event) }

    val selected = selectedMark()
    if (selected != null && chipRect.contains(event.x, event.y)) {
      removeMark(selected.id)
      clearSelection()
      onHint?.invoke("Deleted — undo to bring it back")
      return
    }

    val hit = findDraggableAt(event.x, event.y)
    if (hit != null) {
      dragHistory = false
      dragMark = hit
      dragStartRect.set(hit.rect)
      dragFromX = event.x
      dragFromY = event.y
      // Highlight it, but stay quiet: a drag is a move, not a restyle request.
      select(hit, notify = false)
      return
    }

    if (selectionVisible()) {
      val handle = handleAt(event.x, event.y)
      if (handle != 0) {
        handleDrag = handle
        moved = true
        return
      }
    }

    if (tool == PdfTool.HIGHLIGHT) {
      val now = SystemClock.uptimeMillis()
      val near = hypot(event.x - lastTapX, event.y - lastTapY) < 28f * density
      tapCount = if (now - lastTapAt < 320L && near) tapCount + 1 else 1
      lastTapAt = now
      lastTapX = event.x
      lastTapY = event.y
      if (tapCount >= 2) {
        val index = pageAt(event.y)
        ensureTextBoxes(index)
        val char = charIndexAt(index, event.x, event.y)
        if (char >= 0) {
          selectWordOrParagraph(index, char, tapCount >= 3)
          return
        }
      }
      beginSelection(event.x, event.y)
      return
    }

    if (tool == PdfTool.DRAW) {
      beginStroke(event)
      return
    }

    if (tool == PdfTool.NONE) {
      clearSelection()
      select(null)
    }
  }

  private fun handleMove(event: MotionEvent) {
    velocity?.addMovement(event)
    if (handleDrag != 0) {
      val index = pageAt(event.y)
      if (index == selPage) {
        autoScroll(event.y)
        val char = charIndexAt(index, event.x, event.y)
        if (char >= 0) {
          if (handleDrag == HANDLE_START) selAnchor = char else selFocus = char
          invalidate()
        }
      }
      return
    }
    if (marqueeActive) {
      extendMarquee(event.x, event.y)
      return
    }
    if (selecting) {
      extendSelection(event.x, event.y)
      return
    }
    val stroke = activeStroke
    if (stroke != null) {
      extendStroke(stroke, event)
      return
    }
    val dragging = dragMark
    if (dragging != null) {
      val page = dragging.page - 1
      if (page < 0 || page >= pageCount) return
      val w = pageWidthPx(page).coerceAtLeast(1f)
      val h = pageHeightPx(page).coerceAtLeast(1f)
      val dx = (event.x - dragFromX) / w
      val dy = (event.y - dragFromY) / h
      if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) moved = true
      if (moved && !dragHistory) {
        // One snapshot for the whole drag, taken before the first pixel moves.
        recordHistory()
        dragHistory = true
      }
      dragMark?.let { mark ->
        mark.rect.set(
          (dragStartRect.left + dx).coerceIn(-0.02f, 0.98f),
          (dragStartRect.top + dy).coerceIn(-0.02f, 0.98f),
          (dragStartRect.right + dx).coerceIn(0.02f, 1.04f),
          (dragStartRect.bottom + dy).coerceIn(0.02f, 1.04f),
        )
        invalidate()
      }
      return
    }

    val dx = downX - event.x
    val dy = downY - event.y
    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) moved = true
    if (moved) {
      scrollY += dy
      offsetX += dx
      downX = event.x
      downY = event.y
      clampScroll()
      invalidate()
      reportPage()
      parent?.requestDisallowInterceptTouchEvent(true)
    }
  }

  private fun handleUp(event: MotionEvent) {
    // The finger that started a tool action was cancelled by the second one;
    // the lift that follows must not read as a tap.
    if (suppressNextUp) {
      suppressNextUp = false
      resetVelocity()
      return
    }
    if (handleDrag != 0) {
      handleDrag = 0
      resizeAppliedMark()
      resetVelocity()
      return
    }
    if (selecting) {
      commitSelection()
      resetVelocity()
      requestVisibleRenders()
      return
    }
    if (marqueeActive) {
      commitMarquee()
      resetVelocity()
      requestVisibleRenders()
      return
    }
    val stroke = activeStroke
    activeStroke = null
    if (stroke != null) {
      simplifyStroke(stroke)
      if (isStrokeUsable(stroke)) {
        recordHistory()
        marks.add(stroke)
        onMarksChanged?.invoke()
      }
      invalidate()
      resetVelocity()
      return
    }

    val dragging = dragMark
    if (dragging != null) {
      dragMark = null
      dragHistory = false
      if (moved) {
        onMarksChanged?.invoke()
      } else {
        if (dragging.kind == MarkKind.NOTE) {
          onNoteTapped?.invoke(dragging)
        } else {
          // It was a tap, not a drag: now the selection change means "edit me".
          selectedId = null
          select(dragging)
        }
      }
      resetVelocity()
      return
    }

    if (!moved) {
      handleTap(event.x, event.y)
    } else {
      val tracker = velocity
      if (tracker != null) {
        tracker.computeCurrentVelocity(1000)
        val yv = tracker.yVelocity
        if (abs(yv) > 140f) {
          val maxScroll = max(0f, contentHeight - height).roundToInt()
          scroller.fling(0, scrollY.roundToInt(), 0, (-yv).roundToInt(), 0, 0, 0, maxScroll)
          postInvalidateOnAnimation()
        }
      }
    }
    resetVelocity()
    requestVisibleRenders()
  }

  private fun resetVelocity() {
    velocity?.recycle()
    velocity = null
  }

  private fun resetGesture() {
    activeStroke = null
    dragHistory = false
    suppressNextUp = false
    handleDrag = 0
    if (selecting) {
      selecting = false
      selPage = -1
      selAnchor = -1
      selFocus = -1
    }
    dragMark = null
    resetVelocity()
    invalidate()
  }

  private fun handleTap(x: Float, y: Float) {
    if (pageCount <= 0) return
    val note = findNoteAt(x, y)
    if (note != null) {
      select(note)
      onNoteTapped?.invoke(note)
      return
    }
    val index = pageAt(y)
    val point = toPage(index, x, y)
    when (tool) {
      PdfTool.COMMENT -> onCommentAt?.invoke(index + 1, point.x, point.y)
      PdfTool.SIGN -> {
        val pending = pendingSignature
        if (pending == null) {
          onHint?.invoke("Add a signature first")
        } else {
          val placed = pending.copy()
          placed.id = UUID.randomUUID().toString()
          placed.page = index + 1
          val width = placed.rect.width()
          val height = placed.rect.height()
          placed.rect.set(
            (point.x - width / 2f).coerceIn(0f, 1f - width),
            (point.y - height / 2f).coerceIn(0f, 1f - height),
            (point.x + width / 2f).coerceIn(width, 1f),
            (point.y + height / 2f).coerceIn(height, 1f),
          )
          recordHistory()
          marks.add(placed)
          select(placed)
          onMarksChanged?.invoke()
          onHint?.invoke("Drag it where you want it")
          invalidate()
        }
      }
      else -> {
        val hit = findMarkAt(x, y)
        select(hit)
      }
    }
  }

  // ---- strokes ------------------------------------------------------------

  /** Pen strokes stay freehand; every markup style is a text selection. */
  private fun beginStroke(event: MotionEvent) {
    clearSelection()
    val index = pageAt(event.y)
    val point = toPage(index, event.x, event.y)
    val mark = PdfMark(
      id = UUID.randomUUID().toString(),
      kind = MarkKind.INK,
      page = index + 1,
      color = strokeColor(),
      width = inkWidth(),
    )
    mark.points = floatArrayOf(point.x, point.y)
    mark.rect.set(point.x, point.y, point.x, point.y)
    activeStroke = mark
  }

  private fun extendStroke(stroke: PdfMark, event: MotionEvent) {
    val page = stroke.page - 1
    if (page < 0 || page >= pageCount) return
    val left = pageLeft(page)
    val top = pageTops[page] - scrollY
    val w = pageWidthPx(page).coerceAtLeast(1f)
    val h = pageHeightPx(page).coerceAtLeast(1f)
    val nx = ((event.x - left) / w).coerceIn(0f, 1f)
    val ny = ((event.y - top) / h).coerceIn(0f, 1f)
    val points = stroke.points
    if (points.size >= 2) {
      val lastX = points[points.size - 2]
      val lastY = points[points.size - 1]
      if (hypot(nx - lastX, ny - lastY) < 0.002f) return
    }
    val next = points.copyOf(points.size + 2)
    next[next.size - 2] = nx
    next[next.size - 1] = ny
    stroke.points = next
    stroke.rect.set(
      min(stroke.rect.left, nx),
      min(stroke.rect.top, ny),
      max(stroke.rect.right, nx),
      max(stroke.rect.bottom, ny),
    )
    invalidate()
  }

  private fun isStrokeUsable(stroke: PdfMark): Boolean = stroke.points.size >= 4

  /**
   * Ramer–Douglas–Peucker over the finished stroke, then a light resample: the
   * curve keeps its shape while the stored (and serialised) point count drops
   * by roughly an order of magnitude on fast strokes.
   */
  private fun simplifyStroke(stroke: PdfMark) {
    if (stroke.points.size <= 6) return
    val simplified = PdfMarkGeometry.simplify(stroke.points, 0.0012f)
    if (simplified.size >= 4 && simplified.size < stroke.points.size) stroke.points = simplified
  }

  // ---- text selection -----------------------------------------------------

  /**
   * Char boxes for a page, loaded once in the background. Selection then works
   * purely in memory, so dragging never waits on PDFium.
   */
  private fun ensureTextBoxes(index: Int) {
    if (index < 0 || index >= pageCount) return
    if (textBoxes.get(index) != null || textLoading.contains(index)) return
    val doc = document ?: return
    textLoading.add(index)
    val generation = openGeneration
    renderExecutor.execute {
      val boxes = ArrayList<RectF>()
      try {
        PdfiumLock.withLock {
          val page = doc.openPage(index) ?: return@withLock
          try {
            val textPage = page.openTextPage()
            try {
              val count = textPage.textPageCountChars()
              for (i in 0 until count) {
                // `textPageGetCharBox` reports page space, where y grows
                // upwards, so `top` is numerically greater than `bottom` and a
                // raw RectF(left, top, right, bottom) reads as empty. Order the
                // corners first, then keep the ones with area.
                val box = try {
                  textPage.textPageGetCharBox(i)?.let { raw ->
                    RectF(
                      min(raw.left, raw.right),
                      min(raw.top, raw.bottom),
                      max(raw.left, raw.right),
                      max(raw.top, raw.bottom),
                    )
                  }
                } catch (_: Throwable) {
                  null
                }
                if (box != null && box.width() > 0f && box.height() > 0f) {
                  boxes.add(RectF(box))
                } else {
                  boxes.add(RectF())
                }
              }
            } finally {
              try {
                textPage.close()
              } catch (_: Throwable) {
              }
            }
          } finally {
            try {
              page.close()
            } catch (_: Throwable) {
            }
          }
        }
      } catch (_: Throwable) {
      }
      val normalised = normaliseTextBoxes(boxes, index)
      mainHandler.post {
        textLoading.remove(index)
        if (generation != openGeneration) return@post
        textBoxes.put(index, normalised)
        boxArrays.put(index, packBoxes(normalised))
        // The finger that started the drag had no boxes to hit yet: finish the
        // gesture now that they are here.
        val pending = pendingSelect
        if (pending != null && pending.first == index) {
          pendingSelect = null
          beginSelection(pending.second.x, pending.second.y)
        }
      }
    }
  }

  /**
   * Moves char boxes onto the rendered page box.
   *
   * `textPageGetCharBox` can report either page space (unrotated, PDF user
   * space) or display space (what the renderer draws). Rather than assume, the
   * first rotated page that has text decides: the mapping that keeps the glyphs
   * inside the page wins, with a reading-direction test settling the 180 degree
   * case where both mappings fit. Everything is cached in display space, so the
   * rest of the editor never has to think about rotation again.
   */
  private fun normaliseTextBoxes(raw: List<RectF>, index: Int): List<RectF> {
    if (raw.isEmpty()) return raw
    return raw.map { box -> if (box.isEmpty) RectF() else pageBoxToDisplay(box, index) }
  }

  /**
   * Page space (crop box relative, y up) to the rendered page box, applying the
   * page's /Rotate. Returns a rect in display points.
   */
  private fun pageBoxToDisplay(box: RectF, index: Int): RectF {
    val display = pageSize(index)
    val rotation = pageRotations.get(index, 0)
    // Page space starts at the crop box's lower left and grows upwards. Only a
    // crop box that actually looks like one is trusted: this PDFium build can
    // report an inverted or empty box, and believing it would push every glyph
    // off the page.
    val crop = pageCrops.get(index)?.takeIf { candidate ->
      candidate.width() > 1f && candidate.height() > 1f &&
        candidate.left >= -1f && candidate.top >= -1f &&
        candidate.right <= display.x + 1f && candidate.bottom <= display.y + 1f
    }
    val originX = crop?.let { min(it.left, it.right) } ?: 0f
    val originY = crop?.let { max(it.top, it.bottom) } ?: display.y

    val left = min(box.left, box.right) - originX
    val right = max(box.left, box.right) - originX
    val top = min(originY - box.bottom, originY - box.top)
    val bottom = max(originY - box.bottom, originY - box.top)
    val w = display.x
    val h = display.y
    return when (rotation) {
      1 -> RectF(w - bottom, left, w - top, right)
      2 -> RectF(w - right, h - bottom, w - left, h - top)
      3 -> RectF(top, h - right, bottom, h - left)
      else -> RectF(left, top, right, bottom)
    }
  }

  /** Nearest character to a touch point, in page points. */
  private fun charIndexAt(index: Int, x: Float, y: Float): Int {
    val boxes = boxArrays.get(index) ?: return -1
    val left = pageLeft(index)
    val top = pageTops[index] - scrollY
    val scale = pxPerPoint().coerceAtLeast(0.0001f)
    val px = (x - left) / scale
    val py = (y - top) / scale
    return PdfMarkGeometry.charIndexAt(
      boxes,
      px,
      py,
      insideSlop = 10f * density / scale,
      fallbackSlop = 22f * density / scale,
    )
  }

  /** Packs char boxes into the flat form the pure geometry code takes. */
  private fun packBoxes(boxes: List<RectF>): FloatArray {
    val out = FloatArray(boxes.size * 4)
    boxes.forEachIndexed { i, box ->
      out[i * 4] = box.left
      out[i * 4 + 1] = box.top
      out[i * 4 + 2] = box.right
      out[i * 4 + 3] = box.bottom
    }
    return out
  }

  /** One box per text line between two character indices, in page points. */
  private fun selectionRects(index: Int, from: Int, to: Int): List<RectF> {
    val boxes = boxArrays.get(index) ?: return emptyList()
    val flat = PdfMarkGeometry.selectionRects(boxes, from, to)
    if (flat.isEmpty()) return emptyList()
    val rects = ArrayList<RectF>(flat.size / 4)
    var i = 0
    while (i < flat.size) {
      rects.add(RectF(flat[i], flat[i + 1], flat[i + 2], flat[i + 3]))
      i += 4
    }
    return rects
  }

  // ---- marquee highlight (pages with no text layer) -----------------------

  /**
   * Scanned pages return no characters, so a text drag could only fail. The
   * same drag becomes a rectangle instead: drag a box, tint it, same colour and
   * opacity as a text highlight.
   */
  private fun beginMarquee(index: Int, x: Float, y: Float) {
    val w = pageWidthPx(index)
    val h = pageHeightPx(index)
    if (w <= 0f || h <= 0f) return
    val left = pageLeft(index)
    val top = pageTops[index] - scrollY
    marqueePage = index
    marqueeStartX = ((x - left) / w).coerceIn(0f, 1f)
    marqueeStartY = ((y - top) / h).coerceIn(0f, 1f)
    marqueeEndX = marqueeStartX
    marqueeEndY = marqueeStartY
    marqueeActive = true
    selecting = false
    selPage = -1
    appliedMarkId = null
    pendingSelect = null
    loupeX = x
    loupeY = y
    invalidate()
  }

  private fun extendMarquee(x: Float, y: Float) {
    val index = marqueePage
    if (index < 0) return
    val w = pageWidthPx(index)
    val h = pageHeightPx(index)
    if (w <= 0f || h <= 0f) return
    val left = pageLeft(index)
    val top = pageTops[index] - scrollY
    marqueeEndX = ((x - left) / w).coerceIn(0f, 1f)
    marqueeEndY = ((y - top) / h).coerceIn(0f, 1f)
    invalidate()
  }

  private fun commitMarquee() {
    marqueeActive = false
    val index = marqueePage
    marqueePage = -1
    if (index < 0 || index >= pageCount) {
      invalidate()
      return
    }
    val left = min(marqueeStartX, marqueeEndX)
    val right = max(marqueeStartX, marqueeEndX)
    val top = min(marqueeStartY, marqueeEndY)
    val bottom = max(marqueeStartY, marqueeEndY)
    // A tap, not a box: keep the page clean and say why nothing happened.
    if (right - left < 0.01f || bottom - top < 0.01f) {
      performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
      onHint?.invoke("Drag a box to highlight this page")
      invalidate()
      return
    }
    val mark = PdfMark(
      id = UUID.randomUUID().toString(),
      kind = MarkKind.HIGHLIGHT,
      page = index + 1,
      color = markupColor(),
      glyph = highlightStyle,
    )
    mark.rects.add(RectF(left, top, right, bottom))
    mark.rect.set(mark.bounds())
    recordHistory()
    marks.add(mark)
    select(mark)
    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    onMarksChanged?.invoke()
    onHint?.invoke("Area highlighted")
    invalidate()
  }

  private fun drawMarquee(canvas: Canvas) {
    if (!marqueeActive) return
    val index = marqueePage
    if (index < 0 || index >= pageCount) return
    val left = pageLeft(index)
    val top = pageTops[index] - scrollY
    val w = pageWidthPx(index)
    val h = pageHeightPx(index)
    val l = left + min(marqueeStartX, marqueeEndX) * w
    val r = left + max(marqueeStartX, marqueeEndX) * w
    val t = top + min(marqueeStartY, marqueeEndY) * h
    val b = top + max(marqueeStartY, marqueeEndY) * h
    markPaint.color = (markupColor() and 0x00FFFFFF) or 0x2E000000
    canvas.drawRect(l, t, r, max(t + 1f, b), markPaint)
    borderPaint.color = (markupColor() and 0x00FFFFFF) or 0xFF000000.toInt()
    borderPaint.strokeWidth = 1.5f * density
    canvas.drawRect(l, t, r, max(t + 1f, b), borderPaint)
  }

  /** A range is on screen while dragging or while its handles are attached. */
  private fun selectionVisible(): Boolean =
    selPage >= 0 && selAnchor >= 0 && selFocus >= 0 && (selecting || appliedMarkId != null)

  fun clearSelection() {
    selecting = false
    selPage = -1
    selAnchor = -1
    selFocus = -1
    appliedMarkId = null
    invalidate()
  }

  /** Centre points of the two grab handles, in view pixels. */
  private fun selectionHandles(index: Int): Pair<PointF, PointF>? {
    val rects = selectionRects(index, selAnchor, selFocus)
    if (rects.isEmpty()) return null
    val left = pageLeft(index)
    val top = pageTops[index] - scrollY
    val w = pageWidthPx(index)
    val h = pageHeightPx(index)
    val first = rects.first()
    val last = rects.last()
    return PointF(left + first.left * w, top + (first.top + first.bottom) / 2f * h) to
      PointF(left + last.right * w, top + (last.top + last.bottom) / 2f * h)
  }

  private fun handleAt(x: Float, y: Float): Int {
    if (selPage < 0) return 0
    val handles = selectionHandles(selPage) ?: return 0
    val radius = 18f * density
    if (hypot(x - handles.first.x, y - handles.first.y) <= radius) return HANDLE_START
    if (hypot(x - handles.second.x, y - handles.second.y) <= radius) return HANDLE_END
    return 0
  }

  /** Re-fits the existing mark to the adjusted range instead of adding one. */
  private fun resizeAppliedMark() {
    val id = appliedMarkId ?: return
    val mark = marks.firstOrNull { it.id == id } ?: return
    val index = selPage
    if (index < 0 || index >= pageCount) return
    val rects = selectionRects(index, selAnchor, selFocus)
    val page = pageSize(index)
    if (rects.isEmpty() || page.x <= 0f || page.y <= 0f) return
    val normalised = rects.map { box ->
      RectF(
        box.left / page.x,
        box.top / page.y,
        box.right / page.x,
        box.bottom / page.y,
      )
    }
    val unchanged = normalised.size == mark.rects.size &&
      normalised.indices.all { i ->
        val a = mark.rects[i]
        val b = normalised[i]
        abs(a.left - b.left) < 0.0004f && abs(a.top - b.top) < 0.0004f &&
          abs(a.right - b.right) < 0.0004f && abs(a.bottom - b.bottom) < 0.0004f
      }
    if (unchanged) return
    recordHistory()
    mark.rects.clear()
    mark.rects.addAll(normalised)
    mark.rect.set(mark.bounds())
    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    onMarksChanged?.invoke()
    invalidate()
  }

  /** Double tap selects the word, triple tap the paragraph around it. */
  private fun selectWordOrParagraph(index: Int, char: Int, paragraph: Boolean) {
    val range = if (paragraph) expandParagraph(index, char) else expandWord(index, char)
    selPage = index
    selAnchor = range.first
    selFocus = range.second
    selecting = true
    appliedMarkId = null
    commitSelection()
  }

  private fun sameLine(a: RectF, b: RectF): Boolean =
    min(a.bottom, b.bottom) - max(a.top, b.top) > min(a.height(), b.height()) * 0.4f

  /** Widens a character index to the word it belongs to, using glyph gaps. */
  private fun expandWord(index: Int, char: Int): Pair<Int, Int> {
    val boxes = textBoxes.get(index) ?: return char to char
    if (char !in boxes.indices) return char to char
    val base = boxes[char]
    if (base.isEmpty) return char to char
    val gapLimit = base.height() * 0.55f
    var start = char
    while (start > 0) {
      val previous = boxes[start - 1]
      if (previous.isEmpty || !sameLine(previous, base)) break
      if (boxes[start].left - previous.right > gapLimit) break
      start--
    }
    var end = char
    while (end < boxes.size - 1) {
      val next = boxes[end + 1]
      if (next.isEmpty || !sameLine(next, base)) break
      if (next.left - boxes[end].right > gapLimit) break
      end++
    }
    return start to end
  }

  /** Grows from a character outward while lines stay close enough to be a block. */
  private fun expandParagraph(index: Int, char: Int): Pair<Int, Int> {
    val boxes = textBoxes.get(index) ?: return char to char
    if (char !in boxes.indices) return char to char
    val anchorBox = boxes[char]
    if (anchorBox.isEmpty) return char to char
    val lineHeight = anchorBox.height().coerceAtLeast(0.5f)
    fun adjacent(a: RectF, b: RectF): Boolean {
      if (a.isEmpty || b.isEmpty) return false
      if (sameLine(a, b)) return b.left - a.right < lineHeight * 2.5f
      val gap = b.top - a.bottom
      return gap > -lineHeight * 0.8f && gap < lineHeight * 1.2f
    }
    var start = char
    while (start > 0 && adjacent(boxes[start - 1], boxes[start])) start--
    var end = char
    while (end < boxes.size - 1 && adjacent(boxes[end], boxes[end + 1])) end++
    return start to end
  }

  private fun autoScroll(y: Float) {
    val edge = 52f * density
    val step = 16f * density
    when {
      y < edge -> scrollY -= step
      y > height - edge -> scrollY += step
      else -> return
    }
    clampScroll()
    reportPage()
    requestVisibleRenders()
  }

  private fun beginSelection(x: Float, y: Float) {
    val index = pageAt(y)
    ensureTextBoxes(index)
    if (textBoxes.get(index) == null) {
      // Char boxes load off the UI thread; retry when they land instead of
      // answering "no text here" about a page whose text is simply not parsed
      // yet.
      appliedMarkId = null
      selPage = index
      pendingSelect = index to PointF(x, y)
      return
    }
    if (textBoxes.get(index)?.isEmpty() == true) {
      // No text layer at all — a scanned page. Highlighting text is impossible
      // here, so the drag becomes a marquee instead of silently doing nothing.
      beginMarquee(index, x, y)
      return
    }
    pendingSelect = null
    appliedMarkId = null
    selPage = index
    val char = charIndexAt(index, x, y)
    selAnchor = char
    selFocus = char
    selecting = char >= 0
    loupeX = x
    loupeY = y
    if (char < 0) {
      // Nothing to select here: say so instead of silently doing nothing.
      performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
      onHint?.invoke("No text here — drag across the words themselves")
      selPage = -1
    }
    invalidate()
  }

  private fun extendSelection(x: Float, y: Float) {
    if (!selecting) return
    // Dragging past the edge keeps extending instead of stopping: the page
    // scrolls under the finger.
    autoScroll(y)
    val index = pageAt(y)
    if (index != selPage) return
    val char = charIndexAt(index, x, y)
    if (char < 0) return
    loupeX = x
    loupeY = y
    selFocus = char
    invalidate()
  }

  private fun commitSelection() {
    val index = selPage
    val from = selAnchor
    val to = selFocus
    selecting = false
    selPage = -1
    selAnchor = -1
    selFocus = -1
    if (index < 0 || index >= pageCount || from < 0 || to < 0) {
      invalidate()
      return
    }
    val rects = selectionRects(index, from, to)
    val page = pageSize(index)
    if (rects.isEmpty() || page.x <= 0f || page.y <= 0f) {
      invalidate()
      return
    }
    val mark = PdfMark(
      id = UUID.randomUUID().toString(),
      kind = MarkKind.HIGHLIGHT,
      page = index + 1,
      color = markupColor(),
      glyph = highlightStyle,
    )
    rects.forEach { box ->
      mark.rects.add(
        RectF(
          box.left / page.x,
          box.top / page.y,
          box.right / page.x,
          box.bottom / page.y,
        ),
      )
    }
    mark.rect.set(mark.bounds())
    recordHistory()
    restylePending = false
    marks.add(mark)
    select(mark)
    // The range stays live so its ends can be nudged with the handles.
    appliedMarkId = mark.id
    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    onMarksChanged?.invoke()
    invalidate()
    onHint?.invoke(
      when (highlightStyle) {
        "underline" -> "Text underlined"
        "strikeout" -> "Text struck through"
        else -> "Text highlighted"
      },
    )
  }

  /** Selected colour with the current opacity folded into its alpha. */
  private fun markupColor(): Int {
    val rgb = highlightColor and 0x00FFFFFF
    val alpha = when (highlightStyle) {
      "underline", "strikeout" -> 0xFF
      else -> (highlightOpacity.coerceIn(0.05f, 1f) * 255f).toInt()
    }
    return rgb or (alpha shl 24)
  }

  private fun inkWidth(): Float = 0.0018f + strokeStep.coerceIn(0, STROKE_STEPS - 1) * 0.0017f

  private fun strokeColor(): Int {
    val alpha = (inkOpacity.coerceIn(0.05f, 1f) * 255f).toInt()
    return (inkColor and 0x00FFFFFF) or (alpha shl 24)
  }

  // ---- marks --------------------------------------------------------------

  fun addMark(mark: PdfMark) {
    recordHistory()
    marks.add(mark)
    onMarksChanged?.invoke()
    invalidate()
  }

  fun updateMark(id: String, transform: (PdfMark) -> Unit) {
    val mark = marks.firstOrNull { it.id == id } ?: return
    recordHistory()
    transform(mark)
    onMarksChanged?.invoke()
    invalidate()
  }

  fun removeMark(id: String) {
    if (marks.none { it.id == id }) return
    recordHistory()
    marks.removeAll { it.id == id }
    if (selectedId == id) select(null)
    onMarksChanged?.invoke()
    invalidate()
  }

  fun clearMarks() {
    if (marks.isEmpty()) return
    recordHistory()
    marks.clear()
    select(null)
    clearSelection()
    onMarksChanged?.invoke()
    invalidate()
  }

  // ---- history ------------------------------------------------------------

  /**
   * Undo/redo keeps whole snapshots rather than per-action inverses: a mark is a
   * few hundred bytes of JSON, and this way every edit path — new markup, a
   * restyle, a drag, a delete, "clear all" — is undoable without a bespoke
   * inverse for each.
   */
  private fun recordHistory() {
    undoStack.addLast(PdfMarkCodec.encode(marks))
    while (undoStack.size > HISTORY_LIMIT) undoStack.removeFirst()
    redoStack.clear()
  }

  fun canUndo(): Boolean = undoStack.isNotEmpty()

  fun canRedo(): Boolean = redoStack.isNotEmpty()

  fun redo() {
    if (redoStack.isEmpty()) return
    undoStack.addLast(PdfMarkCodec.encode(marks))
    restoreSnapshot(redoStack.removeLast())
  }

  fun undoMark() {
    if (undoStack.isEmpty()) return
    redoStack.addLast(PdfMarkCodec.encode(marks))
    restoreSnapshot(undoStack.removeLast())
  }

  private fun restoreSnapshot(raw: String) {
    restylePending = false
    val restored = PdfMarkCodec.decode(raw)
    marks.clear()
    marks.addAll(restored)
    select(null)
    clearSelection()
    onMarksChanged?.invoke()
    invalidate()
  }

  /**
   * Arms one snapshot for the restyle that follows. The snapshot is taken on
   * the first actual change, so opening a sheet and closing it again does not
   * leave an undo step that does nothing.
   */
  fun beginRestyle() {
    restylePending = true
  }

  fun restyleMark(id: String, color: Int, glyph: String? = null, width: Float? = null) {
    val mark = marks.firstOrNull { it.id == id } ?: return
    if (restylePending) {
      recordHistory()
      restylePending = false
    }
    mark.color = color
    if (glyph != null) mark.glyph = glyph
    if (width != null) mark.width = width
    onMarksChanged?.invoke()
    invalidate()
  }

  fun notes(): List<PdfMark> = marks.filter { it.kind == MarkKind.NOTE }

  fun noteById(id: String?): PdfMark? = marks.firstOrNull { it.kind == MarkKind.NOTE && it.id == id }

  /** [notify] false only highlights the mark, for a touch that may become a drag. */
  fun select(mark: PdfMark?, notify: Boolean = true) {
    val nextId = mark?.id
    if (nextId == selectedId) return
    selectedId = nextId
    if (notify) onSelectionChanged?.invoke(mark)
    invalidate()
  }

  fun selectedMark(): PdfMark? = marks.firstOrNull { it.id == selectedId }

  fun markById(id: String): PdfMark? = marks.firstOrNull { it.id == id }

  private fun selectById(id: String?) {
    if (id == null) {
      select(null)
      return
    }
    val mark = marks.firstOrNull { it.id == id }
    select(mark)
  }

  fun selectNote(id: String?) {
    selectById(id)
  }

  private fun findNoteAt(x: Float, y: Float): PdfMark? {
    if (pageCount <= 0) return null
    val index = pageAt(y)
    val left = pageLeft(index)
    val top = pageTops[index] - scrollY
    val w = pageWidthPx(index)
    val h = pageHeightPx(index)
    val radius = 20f * density
    return marks.lastOrNull { mark ->
      mark.kind == MarkKind.NOTE && mark.page == index + 1 &&
        hypot(left + mark.rect.centerX() * w - x, top + mark.rect.centerY() * h - y) <= radius
    }
  }

  private fun findDraggableAt(x: Float, y: Float): PdfMark? {
    val note = findNoteAt(x, y)
    if (note != null) return note
    if (pageCount <= 0) return null
    val index = pageAt(y)
    val left = pageLeft(index)
    val top = pageTops[index] - scrollY
    val w = pageWidthPx(index)
    val h = pageHeightPx(index)
    return marks.lastOrNull { mark ->
                mark.isDraggable() && mark.kind != MarkKind.NOTE && mark.page == index + 1 &&
        x >= left + mark.bounds().left * w - 8f * density &&
        x <= left + mark.bounds().right * w + 8f * density &&
        y >= top + mark.bounds().top * h - 8f * density &&
        y <= top + mark.bounds().bottom * h + 8f * density
    }
  }

  private fun findMarkAt(x: Float, y: Float): PdfMark? {
    if (pageCount <= 0) return null
    val index = pageAt(y)
    val left = pageLeft(index)
    val top = pageTops[index] - scrollY
    val w = pageWidthPx(index)
    val h = pageHeightPx(index)
    return marks.lastOrNull { mark ->
      mark.page == index + 1 &&
        x >= left + mark.bounds().left * w &&
        x <= left + mark.bounds().right * w &&
        y >= top + mark.bounds().top * h &&
        y <= top + mark.bounds().bottom * h
    }
  }

  // ---- drawing ------------------------------------------------------------

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    if (pageCount <= 0) {
      val message = if (failed) "This document could not be opened" else "Preparing document…"
      canvas.drawText(message, width / 2f, height / 2f, hintPaint)
      return
    }
    for (index in 0 until pageCount) {
      val top = pageTops[index] - scrollY
      val pageHeight = pageHeightPx(index)
      if (top > height || top + pageHeight < 0) continue
      val left = pageLeft(index)
      val pageWidth = pageWidthPx(index)
      val rect = RectF(left, top, left + pageWidth, top + pageHeight)

      // Soft sheet: white page over a 1px border so stacked pages read apart.
      canvas.drawRect(rect, markPaint.apply { color = Color.WHITE })
      val bitmap = bitmaps[renderKey(index, targetWidth(index))]
      if (bitmap != null && !bitmap.isRecycled) {
        canvas.drawBitmap(bitmap, null, rect, pagePaint)
      }
      canvas.drawRect(rect, borderPaint)

      drawPageMarks(canvas, index, rect)
    }
    drawActiveStroke(canvas)
    drawMarquee(canvas)
    drawTextSelection(canvas)
    drawPins(canvas)
    drawSelection(canvas)
    drawLoupe(canvas)
  }

  /** The stroke being drawn right now, so it follows the finger instead of
   *  appearing only when the finger lifts. */
  private fun drawActiveStroke(canvas: Canvas) {
    val stroke = activeStroke ?: return
    val index = stroke.page - 1
    if (index !in 0 until pageCount) return
    val left = pageLeft(index)
    val top = pageTops[index] - scrollY
    drawMark(canvas, stroke, RectF(left, top, left + pageWidthPx(index), top + pageHeightPx(index)))
  }

  /** The live selection: each line tinted, with grab handles at both ends. */
  private fun drawTextSelection(canvas: Canvas) {
    if (!selectionVisible()) return
    val index = selPage
    if (index !in 0 until pageCount || selAnchor < 0 || selFocus < 0) return
    val rects = selectionRects(index, selAnchor, selFocus)
    if (rects.isEmpty()) return
    val left = pageLeft(index)
    val top = pageTops[index] - scrollY
    val pageWidth = pageWidthPx(index)
    val pageHeight = pageHeightPx(index)
    rects.forEach { box ->
      val l = left + box.left * pageWidth
      val t = top + box.top * pageHeight
      val r = left + box.right * pageWidth
      val b = top + box.bottom * pageHeight
      markPaint.color = 0x2E3B82F6
      canvas.drawRect(l, t, r, b, markPaint)
      borderPaint.color = 0xFF3B82F6.toInt()
      borderPaint.strokeWidth = 1f * density
      canvas.drawRect(l, t, r, b, borderPaint)
    }

    val handles = selectionHandles(index) ?: return
    listOf(handles.first, handles.second).forEach { point ->
      markPaint.color = 0xFF3B82F6.toInt()
      canvas.drawCircle(point.x, point.y, 8f * density, markPaint)
      markPaint.color = Color.WHITE
      canvas.drawCircle(point.x, point.y, 3.4f * density, markPaint)
    }
  }

  private fun drawLoupe(canvas: Canvas) {
    if (!selecting) return
    val index = selPage
    if (index !in 0 until pageCount) return
    val bitmap = bitmaps[renderKey(index, targetWidth(index))] ?: return
    if (bitmap.isRecycled) return
    val left = pageLeft(index)
    val top = pageTops[index] - scrollY
    val pageWidth = pageWidthPx(index).coerceAtLeast(1f)
    val pageHeight = pageHeightPx(index).coerceAtLeast(1f)
    if (bitmap.width <= 0 || bitmap.height <= 0) return

    val nx = ((loupeX - left) / pageWidth).coerceIn(0f, 1f)
    val ny = ((loupeY - top) / pageHeight).coerceIn(0f, 1f)
    val bitmapToView = pageWidth / bitmap.width
    val radius = 62f * density
    val zoom = 2.1f
    val sample = radius * bitmapToView / zoom
    val cx = nx * bitmap.width
    val cy = ny * bitmap.height
    val src = RectF(cx - sample, cy - sample, cx + sample, cy + sample)
    src.intersect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
    if (src.isEmpty) return

    val destCx = loupeX.coerceIn(radius, width - radius)
    val destCy = (loupeY - radius * 1.55f).coerceIn(radius, height - radius)
    val dest = RectF(destCx - radius, destCy - radius, destCx + radius, destCy + radius)

    loupeFill.color = Color.WHITE
    canvas.drawCircle(destCx, destCy, radius, loupeFill)
    loupeClip.reset()
    loupeClip.addCircle(destCx, destCy, radius, Path.Direction.CW)
    val srcRect = android.graphics.Rect()
    src.roundOut(srcRect)
    canvas.save()
    canvas.clipPath(loupeClip)
    canvas.drawBitmap(bitmap, srcRect, dest, loupeBitmap)
    canvas.restore()
    loupeRing.color = 0x55000000
    loupeRing.strokeWidth = 2.5f * density
    canvas.drawCircle(destCx, destCy, radius, loupeRing)
    loupeRing.color = Color.WHITE
    loupeRing.strokeWidth = 1.5f * density
    canvas.drawCircle(destCx, destCy, radius - 2.5f * density, loupeRing)
  }

  private fun drawPageMarks(canvas: Canvas, index: Int, rect: RectF) {
    for (mark in marks) {
      if (mark.page != index + 1) continue
      drawMark(canvas, mark, rect)
    }
  }

  /**
   * Ink path through midpoint-quadratic curves: the stroke follows the finger
   * without the polyline corners that make fast handwriting look jagged.
   */
  private fun inkPath(points: FloatArray, left: Float, top: Float, w: Float, h: Float): Path {
    val path = Path()
    val count = points.size / 2
    if (count < 2) return path
    var previousX = left + points[0] * w
    var previousY = top + points[1] * h
    path.moveTo(previousX, previousY)
    var i = 1
    while (i < count - 1) {
      val x = left + points[i * 2] * w
      val y = top + points[i * 2 + 1] * h
      val nextX = left + points[(i + 1) * 2] * w
      val nextY = top + points[(i + 1) * 2 + 1] * h
      path.quadTo(x, y, (x + nextX) / 2f, (y + nextY) / 2f)
      previousX = nextX
      previousY = nextY
      i++
    }
    path.lineTo(previousX, previousY)
    return path
  }

  private fun drawMark(canvas: Canvas, mark: PdfMark, rect: RectF) {
    val pageWidth = rect.width()
    val pageHeight = rect.height()
    run {
      when (mark.kind) {
        MarkKind.HIGHLIGHT -> {
          // One box per selected line, whatever the style.
          val boxes = if (mark.rects.isNotEmpty()) mark.rects else listOf(RectF(mark.rect))
          val style = mark.glyph.ifBlank { "highlight" }
          boxes.forEach { box ->
            val left = rect.left + box.left * pageWidth
            val top = rect.top + box.top * pageHeight
            val right = rect.left + box.right * pageWidth
            val bottom = rect.top + box.bottom * pageHeight
            when (style) {
              "underline" -> {
                strokePaint.color = mark.color
                strokePaint.strokeWidth = max(1.8f * density, pageWidth * 0.0032f)
                val y = bottom - strokePaint.strokeWidth * 0.35f
                canvas.drawLine(left, y, right, y, strokePaint)
              }
              "strikeout" -> {
                strokePaint.color = mark.color
                strokePaint.strokeWidth = max(1.8f * density, pageWidth * 0.0032f)
                val y = (top + bottom) / 2f
                canvas.drawLine(left, y, right, y, strokePaint)
              }
              else -> {
                markPaint.color = mark.color
                canvas.drawRect(left, top, right, bottom, markPaint)
              }
            }
          }
        }
        MarkKind.INK -> {
          if (mark.points.size < 4) return@run
          strokePaint.color = mark.color
          strokePaint.strokeWidth = max(1.6f * density, mark.width * pageWidth)
          canvas.drawPath(inkPath(mark.points, rect.left, rect.top, pageWidth, pageHeight), strokePaint)
        }
        MarkKind.SIGNATURE -> {
          if (mark.points.size < 4) return@run
          // Signature points are normalised to the mark's own box, so moving or
          // resizing the mark carries the strokes with it.
          val boxLeft = rect.left + mark.rect.left * pageWidth
          val boxTop = rect.top + mark.rect.top * pageHeight
          val boxWidth = mark.rect.width() * pageWidth
          val boxHeight = mark.rect.height() * pageHeight
          val path = Path()
          path.moveTo(boxLeft + mark.points[0] * boxWidth, boxTop + mark.points[1] * boxHeight)
          var i = 2
          while (i <= mark.points.size - 2) {
            path.lineTo(boxLeft + mark.points[i] * boxWidth, boxTop + mark.points[i + 1] * boxHeight)
            i += 2
          }
          strokePaint.color = mark.color
          strokePaint.strokeWidth = max(1.4f * density, mark.width * boxWidth)
          canvas.drawPath(path, strokePaint)
        }
        MarkKind.STAMP -> {
          stampPaint.color = mark.color
          stampPaint.textSize = max(12f * density, mark.rect.height() * pageHeight * 0.86f)
          val glyph = mark.text.ifBlank { stampGlyph(mark.glyph) }
          val cx = rect.left + mark.rect.centerX() * pageWidth
          val cy = rect.top + mark.rect.centerY() * pageHeight
          val half = stampPaint.measureText(glyph) / 2f
          canvas.drawText(glyph, cx - half, cy - (stampPaint.ascent() + stampPaint.descent()) / 2f, stampPaint)
        }
        else -> Unit
      }
    }
  }

  private fun stampGlyph(glyph: String): String = when (glyph) {
    "check" -> "\u2713"
    "cross" -> "\u2715"
    "dot" -> "\u25CF"
    "box" -> "\u25A1"
    "line" -> "\u2014"
    else -> glyph
  }

  private fun drawPins(canvas: Canvas) {
    val radius = 13f * density
    for (mark in marks) {
      if (mark.kind != MarkKind.NOTE) continue
      val index = mark.page - 1
      if (index !in 0 until pageCount) continue
      val left = pageLeft(index)
      val top = pageTops[index] - scrollY
      val pageWidth = pageWidthPx(index)
      val pageHeight = pageHeightPx(index)
      if (top > height || top + pageHeight < 0) continue
      val cx = left + mark.rect.centerX() * pageWidth
      val cy = top + mark.rect.centerY() * pageHeight

      // Ambient shadow, then the pin itself: a white disc with a coloured ring
      // reads on both light and dark pages.
      shadowPaint.color = 0x33000000
      canvas.drawCircle(cx, cy + 1.5f * density, radius + density, shadowPaint)
      markPaint.color = Color.WHITE
      canvas.drawCircle(cx, cy, radius, markPaint)
      borderPaint.color = mark.color
      borderPaint.strokeWidth = 2.2f * density
      canvas.drawCircle(cx, cy, radius, borderPaint)
      borderPaint.strokeWidth = 1f

      // Comment bubble glyph.
      val bubbleLeft = cx - radius * 0.46f
      val bubbleTop = cy - radius * 0.42f
      val bubbleRight = cx + radius * 0.46f
      val bubbleBottom = cy + radius * 0.28f
      val bubble = RectF(bubbleLeft, bubbleTop, bubbleRight, bubbleBottom)
      pinGlyphPaint.color = mark.color
      pinGlyphPaint.strokeWidth = 1.5f * density
      canvas.drawRoundRect(bubble, 2.4f * density, 2.4f * density, pinGlyphPaint)
      val tail = Path()
      tail.moveTo(cx - radius * 0.1f, bubbleBottom)
      tail.lineTo(cx - radius * 0.1f, bubbleBottom + radius * 0.30f)
      tail.lineTo(cx + radius * 0.26f, bubbleBottom)
      canvas.drawPath(tail, pinGlyphPaint)
    }
  }

  private fun drawSelection(canvas: Canvas) {
    val mark = selectedMark() ?: run {
      chipRect.setEmpty()
      return
    }
    val index = mark.page - 1
    if (index !in 0 until pageCount) return
    val left = pageLeft(index)
    val top = pageTops[index] - scrollY
    val pageWidth = pageWidthPx(index)
    val pageHeight = pageHeightPx(index)

    val box = if (mark.kind == MarkKind.NOTE) {
      val cx = left + mark.rect.centerX() * pageWidth
      val cy = top + mark.rect.centerY() * pageHeight
      val r = 18f * density
      RectF(cx - r, cy - r, cx + r, cy + r)
    } else {
      val bounds = mark.bounds()
      RectF(
        left + bounds.left * pageWidth,
        top + bounds.top * pageHeight,
        left + bounds.right * pageWidth,
        top + bounds.bottom * pageHeight,
      )
    }
    canvas.drawRoundRect(box, 6f * density, 6f * density, dashPaint)

    // Delete chip floats above the selection, like the Acrobat handles.
    val chipRadius = 17f * density
    val chipCx = box.centerX()
    val chipCy = box.top - chipRadius - 10f * density
    chipRect.set(chipCx - chipRadius, chipCy - chipRadius, chipCx + chipRadius, chipCy + chipRadius)
    shadowPaint.color = 0x26000000
    canvas.drawCircle(chipCx, chipCy + 2f * density, chipRadius + 2f * density, shadowPaint)
    chipPaint.color = Color.WHITE
    canvas.drawCircle(chipCx, chipCy, chipRadius, chipPaint)
    borderPaint.color = 0xFFE3E5E9.toInt()
    borderPaint.strokeWidth = 1f * density
    canvas.drawCircle(chipCx, chipCy, chipRadius, borderPaint)
    borderPaint.color = 0xFFDFE1E6.toInt()
    PdfIconPainter.draw(
      canvas,
      PdfIcon.TRASH,
      chipCx,
      chipCy,
      chipRadius * 1.05f,
      PdfUi.INK,
      1.5f * density,
    )
  }

  private companion object {
    const val MIN_PINCH = 0.7f
    const val MAX_PINCH = 6f
    const val MAX_PAGE_PIXELS = 2_600_000f
    /** Selection grab handles: start of the range, end of the range. */
    const val HANDLE_START = 1
    const val HANDLE_END = 2
    const val HISTORY_LIMIT = 80
  }
}
