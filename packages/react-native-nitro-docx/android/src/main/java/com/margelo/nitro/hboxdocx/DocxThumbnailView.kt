package com.margelo.nitro.hboxdocx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.View

/** Draws one page at the requested scale; used by the thumbnail sheet. */
class DocxThumbnailView(context: Context) : View(context) {
  var documentId: String = ""
  var pageIndex: Int = 0
  var pageScale: Float = 0.2f
  var palette: DocxPalette? = null

  private var cachedLayout: DocxLayout? = null
  private var cachedLayoutKey: String = ""

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    canvas.drawColor(Color.TRANSPARENT)

    val checkpoint = canvas.save()
    try {
      val session = DocxEngineRegistry.get(documentId) ?: return
      val theme = palette ?: return
      val key = "${System.identityHashCode(session)}|${theme.pageDarkMode}"
      val layout = cachedLayout?.takeIf { cachedLayoutKey == key }
        ?: session.layout("markup", theme.pageDarkMode).also {
          cachedLayout = it
          cachedLayoutKey = key
        }
      if (pageIndex < 0 || pageIndex >= layout.pageCount) return

      // `pageScale` is dp per point; the canvas works in pixels.
      val pxPerPoint = pageScale * resources.displayMetrics.density
      canvas.scale(pxPerPoint, pxPerPoint)
      layout.drawPage(canvas, pageIndex, theme)
    } catch (error: Throwable) {
      // Thumbnails are decorative; never let one abort the host view.
      canvas.restoreToCount(checkpoint)
    }
  }
}
