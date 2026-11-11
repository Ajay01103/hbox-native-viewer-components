package com.margelo.nitro.hboxpdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Draws a single PDF page with PDFium.
 *
 * Rasterization runs on a private render thread and the result is kept as a
 * bitmap that is drawn (and upscaled) on the UI thread. That matters for two
 * reasons:
 *
 *  - allocating a full-resolution bitmap inside `onDraw` allocates tens of MB
 *    per frame while scrolling, which trips the GC and ends in a native OOM;
 *  - recycling a bitmap that a hardware `Canvas` has just recorded makes the
 *    render thread read freed memory, which aborts the whole process.
 *
 * Bitmaps here are therefore never recycled while the view is alive; they are
 * simply dropped and left to the GC, which only frees them once no display list
 * references them any more.
 */
class PdfPageAndroidView(context: Context) : View(context) {
  var documentId: String = ""
    set(value) {
      if (field != value) {
        field = value
        requestRender()
      }
    }

  var pageIndex: Int = 0
    set(value) {
      if (field != value) {
        field = value
        requestRender()
      }
    }

  var rotationDegrees: Int = 0
    set(value) {
      if (field != value) {
        field = value
        requestRender()
      }
    }

  var highlights: DoubleArray = doubleArrayOf()
    set(value) {
      field = value
      invalidate()
    }

  private val highlightPaint = Paint().apply {
    color = Color.argb(90, 255, 214, 0)
    style = Paint.Style.FILL
  }
  private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
  private val mainHandler = Handler(Looper.getMainLooper())
  private val generation = AtomicInteger(0)

  private var pageBitmap: Bitmap? = null
  /** Second buffer, reused for the next rasterization of the same size. */
  private var spareBitmap: Bitmap? = null
  private var renderedKey: String = ""
  private var pendingKey: String = ""
  private var attached = true

  /** Renders are tiny and sequential, one thread is enough. */
  private val renderer = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "hbox-pdf-page").apply { isDaemon = true }
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    requestRender()
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    attached = true
    requestRender()
  }

  override fun onDetachedFromWindow() {
    // Cancel every in-flight render for this (recycled) view and drop the
    // buffers; the next binding starts clean instead of painting a stale page.
    attached = false
    generation.incrementAndGet()
    pageBitmap = null
    spareBitmap = null
    renderedKey = ""
    pendingKey = ""
    super.onDetachedFromWindow()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    canvas.drawColor(Color.WHITE)

    val bitmap = pageBitmap
    if (bitmap != null && !bitmap.isRecycled) {
      drawPageBitmap(canvas, bitmap)
    } else {
      // Nothing rasterized yet (first frame, or the page failed to render).
      requestRender()
    }

    drawHighlights(canvas)
  }

  private fun drawPageBitmap(canvas: Canvas, bitmap: Bitmap) {
    val rotated = rotationDegrees == 90 || rotationDegrees == 270
    val targetWidth = (if (rotated) height else width).toFloat()
    val targetHeight = (if (rotated) width else height).toFloat()
    if (targetWidth <= 0f || targetHeight <= 0f) return

    canvas.save()
    canvas.rotate(rotationDegrees.toFloat(), width / 2f, height / 2f)
    val left = (width - targetWidth) / 2f
    val top = (height - targetHeight) / 2f
    canvas.drawBitmap(
      bitmap,
      null,
      RectF(left, top, left + targetWidth, top + targetHeight),
      bitmapPaint,
    )
    canvas.restore()
  }

  private fun requestRender() {
    val id = documentId
    val page = pageIndex
    val rotation = rotationDegrees
    val viewWidth = width
    val viewHeight = height
    if (id.isEmpty() || viewWidth <= 0 || viewHeight <= 0) return

    val key = "$id|$page|$viewWidth|$viewHeight|$rotation"
    if (key == renderedKey || key == pendingKey) return
    pendingKey = key
    val requestGeneration = generation.incrementAndGet()

    renderer.execute {
      val target = spareBitmap
      val bitmap = renderPage(id, page, rotation, viewWidth, viewHeight, requestGeneration, target)
      mainHandler.post {
        if (!attached || generation.get() != requestGeneration) {
          // Stale request (recycled view or newer props): keep the buffer for
          // reuse instead of allocating another one, and paint nothing.
          if (bitmap != null) spareBitmap = bitmap
          return@post
        }
        pendingKey = ""
        renderedKey = key
        if (bitmap != null) {
          // Swap buffers: the frame that is on screen keeps its bitmap alive
          // while the previous one becomes the next render target.
          spareBitmap = pageBitmap?.takeIf { it !== bitmap }
          pageBitmap = bitmap
          invalidate()
        }
      }
    }
  }

  private fun renderPage(
    id: String,
    page: Int,
    rotation: Int,
    viewWidth: Int,
    viewHeight: Int,
    requestGeneration: Int,
    reusable: Bitmap?,
  ): Bitmap? {
    return try {
      PdfiumLock.withLock {
        if (generation.get() != requestGeneration || !attached) {
          // Recycled or superseded before we even got the lock.
          return@withLock null
        }
        val document = PdfDocumentRegistry.get(id) ?: return@withLock null
        if (page < 0 || page >= document.getPageCount()) return@withLock null

        val rotated = rotation == 90 || rotation == 270
        val targetWidth = if (rotated) viewHeight else viewWidth
        val targetHeight = if (rotated) viewWidth else viewHeight
        if (targetWidth <= 0 || targetHeight <= 0) return@withLock null

        // Keep one page under a few MB of ARGB so several preview pages can
        // coexist in the list without exhausting the heap: at most ~12% of the
        // heap per page, and never more than MAX_PAGE_PIXELS (4 bytes/pixel).
        val byteBudget = Runtime.getRuntime().maxMemory() / 8
        val heapPixels = (byteBudget / 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val pixelBudget = min(MAX_PAGE_PIXELS, heapPixels.coerceAtLeast(MIN_PAGE_PIXELS))
        val requested = targetWidth.toLong() * targetHeight.toLong()
        val factor = if (requested > pixelBudget) {
          sqrt(pixelBudget.toDouble() / requested.toDouble())
        } else {
          1.0
        }
        val renderWidth = (targetWidth * factor).toInt().coerceAtLeast(1)
        val renderHeight = (targetHeight * factor).toInt().coerceAtLeast(1)

        val pdfPage = document.openPage(page) ?: return@withLock null
        try {
          // Reuse the freed buffer when it already matches, so scrolling and
          // re-zooming do not allocate a bitmap per frame.
          val bitmap = if (
            reusable != null &&
            !reusable.isRecycled &&
            reusable.width == renderWidth &&
            reusable.height == renderHeight
          ) {
            reusable.eraseColor(Color.WHITE)
            reusable
          } else {
            Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
          }
          pdfPage.renderPageBitmap(bitmap, 0, 0, renderWidth, renderHeight, true)
          bitmap
        } finally {
          pdfPage.close()
        }
      }
    } catch (error: Throwable) {
      // PDFium can abort on malformed pages. A page that cannot be rasterized
      // must leave a blank page, never take the process down with it.
      null
    }
  }

  private fun drawHighlights(canvas: Canvas) {
    if (highlights.size < 4 || width == 0 || height == 0) return
    // Highlights arrive in page points; scale them into the rendered box.
    val scaleX = width.toDouble()
    val scaleY = height.toDouble()
    var index = 0
    while (index <= highlights.size - 4) {
      val left = (highlights[index] * scaleX).toFloat()
      val top = (highlights[index + 1] * scaleY).toFloat()
      val right = left + (highlights[index + 2] * scaleX).toFloat()
      val bottom = top + (highlights[index + 3] * scaleY).toFloat()
      canvas.drawRect(left, top, right, bottom, highlightPaint)
      index += 4
    }
  }

  private companion object {
    /**
     * 4 MP ≈ 16 MB of ARGB per page: enough for a sharp page at 2× zoom on a
     * 1080 px wide screen, while the heap share below keeps several pages
     * alive at once.
     */
    const val MAX_PAGE_PIXELS = 4_000_000
    const val MIN_PAGE_PIXELS = 320 * 320
  }
}
