package com.margelo.nitro.hboxdocx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.View

/** Draws one page of the document, clipped and scaled to the view's width. */
internal class DocxPageView(context: Context) : View(context) {
  var documentId: String = ""
  var pageIndex: Int = 0
  var mode: String = "markup"
  var palette: DocxPalette? = DocxPalette(
    appBg = "#f4f4f5",
    pageBg = "#ffffff",
    pageBorder = "#e4e4e7",
    text = "#18181b",
    muted = "#71717a",
    selection = "",
    commentHighlight = "",
    insertColor = "",
    deleteColor = "",
    pageDarkMode = false,
  )

  // Resolving the pagination through the session map on every frame is pure
  // overhead during scrolling; remember the last one per document/mode/theme.
  private var cachedLayout: DocxLayout? = null
  private var cachedLayoutKey: String = ""

  private fun resolveLayout(session: DocxSession, theme: DocxPalette): DocxLayout {
    val key = "${System.identityHashCode(session)}|$mode|${theme.pageDarkMode}"
    val current = cachedLayout
    if (current != null && cachedLayoutKey == key) return current
    val resolved = session.layout(mode, theme.pageDarkMode)
    cachedLayout = resolved
    cachedLayoutKey = key
    return resolved
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    canvas.drawColor(parseDocxColor(palette?.appBg, Color.LTGRAY))

    val checkpoint = canvas.save()
    try {
      val session = DocxEngineRegistry.get(documentId) ?: return
      val theme = palette ?: return
      val layout = resolveLayout(session, theme)
      if (pageIndex < 0 || pageIndex >= layout.pageCount) return

      val geometry = layout.model.geometry
      val scale = if (geometry.width > 0f) width / geometry.width else 1f
      canvas.scale(scale, scale)
      layout.drawPage(canvas, pageIndex, theme)
    } catch (error: Throwable) {
      // A malformed paragraph must not crash the RecyclerView that hosts it;
      // the page stays blank and the rest of the document still renders.
      canvas.restoreToCount(checkpoint)
    }
  }
}
