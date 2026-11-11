package com.margelo.nitro.hboxdocx

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan

/**
 * A paginated view of one document in one tracked-changes mode.
 *
 * Text is laid out once into a single `StaticLayout` in point space, then sliced
 * into page-sized line ranges. Drawing a page is just a clipped, translated
 * `draw()`, so scrolling never re-shapes text and page count is exact.
 */
internal class DocxLayout private constructor(
  val model: DocxModel,
  val mode: String,
  private val content: Spanned,
  private val layout: StaticLayout,
  private val pageOfLine: IntArray,
  private val pageFirstLine: IntArray,
  private val anchorOffsets: Map<String, Int>,
  val pageCount: Int,
) {
  val text: String
    get() = content.toString()

  /** 1-based page for an anchor id, or 0 when it is unknown. */
  fun pageForAnchor(anchorId: String): Int {
    if (anchorId.isEmpty()) return 0
    val offset = anchorOffsets[anchorId] ?: return 0
    return pageForOffset(offset)
  }

  fun pageForOffset(offset: Int): Int {
    if (layout.lineCount == 0) return 1
    val clamped = offset.coerceIn(0, maxOf(0, content.length - 1))
    val line = layout.getLineForOffset(clamped).coerceIn(0, layout.lineCount - 1)
    return pageOfLine[line] + 1
  }

  fun pageText(pageIndex: Int): String {
    if (pageCount == 0 || layout.lineCount == 0) return ""
    val page = pageIndex.coerceIn(0, pageCount - 1)
    val firstLine = pageFirstLine[page]
    val lastLine = pageFirstLine.getOrNull(page + 1)?.minus(1) ?: (layout.lineCount - 1)
    val start = layout.getLineStart(firstLine)
    val end = layout.getLineEnd(lastLine)
    return content.subSequence(start, end.coerceAtLeast(start)).toString()
  }

  fun search(query: String, limit: Int): List<Pair<Int, Pair<Int, String>>> {
    if (query.isBlank()) return emptyList()
    val haystack = content.toString()
    val needle = query.lowercase()
    val results = ArrayList<Pair<Int, Pair<Int, String>>>()
    var index = haystack.lowercase().indexOf(needle)
    while (index >= 0 && results.size < limit) {
      val page = pageForOffset(index)
      val start = (index - 40).coerceAtLeast(0)
      val end = (index + query.length + 40).coerceAtMost(haystack.length)
      val snippet = haystack.substring(start, end).replace('\n', ' ').trim()
      results.add(page to (index to snippet))
      index = haystack.lowercase().indexOf(needle, index + needle.length)
    }
    return results
  }

  /**
   * Paints one page into [canvas], which must already be scaled so that one
   * point equals `scale` pixels.
   */
  fun drawPage(canvas: Canvas, pageIndex: Int, palette: DocxPalette) {
    val geometry = model.geometry
    val page = pageIndex.coerceIn(0, maxOf(0, pageCount - 1))
    ensurePaints(palette)

    canvas.drawRect(0f, 0f, geometry.width, geometry.height, backgroundPaint)
    canvas.drawRect(0.5f, 0.5f, geometry.width - 0.5f, geometry.height - 0.5f, borderPaint)

    val contentTop = geometry.marginTop
    val contentBottom = geometry.height - geometry.marginBottom
    val contentLeft = geometry.marginLeft
    val contentRight = geometry.width - geometry.marginRight

    if (layout.lineCount > 0) {
      canvas.save()
      canvas.clipRect(contentLeft, contentTop, contentRight, contentBottom)
      val firstLine = pageFirstLine.getOrElse(page) { 0 }
      canvas.translate(contentLeft, contentTop - layout.getLineTop(firstLine).toFloat())
      layout.draw(canvas)
      canvas.restore()
    }

    if (model.header.isNotEmpty()) {
      canvas.drawText(model.header, contentLeft, geometry.marginTop - 14f, mutedPaint)
    }
    if (model.footer.isNotEmpty()) {
      val footer = model.footer
        .replace("%page%", (page + 1).toString())
        .replace("%pages%", pageCount.toString())
      canvas.drawText(footer, contentLeft, geometry.height - geometry.marginBottom + 26f, mutedPaint)
    }
  }

  // Paints are reused across frames: allocating three of them per page draw
  // dominated scrolling on long documents. They are only rebuilt when the
  // palette actually changes.
  private var paintKey: String? = null
  private val backgroundPaint = Paint()
  private val borderPaint = Paint()
  private val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG)

  private fun ensurePaints(palette: DocxPalette) {
    val key = "${palette.pageBg}|${palette.pageBorder}|${palette.muted}|${palette.text}"
    if (key == paintKey) return
    paintKey = key

    backgroundPaint.color = parseDocxColor(palette.pageBg, Color.WHITE)
    backgroundPaint.style = Paint.Style.FILL

    borderPaint.color = parseDocxColor(palette.pageBorder, Color.LTGRAY)
    borderPaint.style = Paint.Style.STROKE
    borderPaint.strokeWidth = 1f

    mutedPaint.color = parseDocxColor(palette.muted, Color.GRAY)
    mutedPaint.textSize = 8.5f

    layout.paint.color = parseDocxColor(palette.text, Color.BLACK)
  }

  companion object {
    private const val BASE_TEXT_SIZE = 11f

    fun build(model: DocxModel, mode: String, dark: Boolean): DocxLayout {
      val builder = SpannableStringBuilder()
      val anchorOffsets = HashMap<String, Int>()
      val commentColor = 0x59FACC15
      val insertColor = if (dark) 0xFF4ADE80.toInt() else 0xFF16A34A.toInt()
      val deleteColor = if (dark) 0xFFF87171.toInt() else 0xFFDC2626.toInt()

      if (model.paragraphs.isEmpty()) {
        builder.append("This document has no readable text.")
      }

      for (paragraph in model.paragraphs) {
        val paragraphStart = builder.length
        anchorOffsets[paragraph.anchorId] = paragraphStart

        val paragraphTextStart = builder.length
        if (paragraph.marker.isNotEmpty()) {
          builder.append(paragraph.marker).append(' ')
        }
        for (run in paragraph.runs) {
          if (mode == "final" && run.revision == "delete") continue
          if (mode == "original" && run.revision == "insert") continue

          val start = builder.length
          builder.append(run.text)
          val end = builder.length
          if (end <= start) continue

          val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
          if (run.bold) builder.setSpan(StyleSpan(Typeface.BOLD), start, end, flags)
          if (run.italic) builder.setSpan(StyleSpan(Typeface.ITALIC), start, end, flags)
          if (run.underline) builder.setSpan(UnderlineSpan(), start, end, flags)
          if (run.strike) builder.setSpan(StrikethroughSpan(), start, end, flags)

          if (run.sizePt > 0f) {
            val relative = (run.sizePt / BASE_TEXT_SIZE).coerceIn(0.6f, 2.5f)
            builder.setSpan(RelativeSizeSpan(relative), start, end, flags)
          }
          if (!dark && run.color != null) {
            builder.setSpan(ForegroundColorSpan(run.color), start, end, flags)
          }
          if (run.highlight != null) {
            builder.setSpan(BackgroundColorSpan(run.highlight), start, end, flags)
          }
          if (run.commentIds.isNotEmpty()) {
            builder.setSpan(BackgroundColorSpan(commentColor), start, end, flags)
          }
          if (mode == "markup" && run.revision == "insert") {
            builder.setSpan(UnderlineSpan(), start, end, flags)
            builder.setSpan(ForegroundColorSpan(insertColor), start, end, flags)
          }
          if (mode == "markup" && run.revision == "delete") {
            builder.setSpan(StrikethroughSpan(), start, end, flags)
            builder.setSpan(ForegroundColorSpan(deleteColor), start, end, flags)
          }
        }

        val paragraphTextEnd = builder.length
        if (paragraph.headingLevel in 1..6) {
          val size = when (paragraph.headingLevel) {
            1 -> 1.6f
            2 -> 1.4f
            3 -> 1.25f
            else -> 1.12f
          }
          if (paragraphTextEnd > paragraphTextStart) {
            builder.setSpan(
              RelativeSizeSpan(size),
              paragraphTextStart,
              paragraphTextEnd,
              Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            builder.setSpan(
              StyleSpan(Typeface.BOLD),
              paragraphTextStart,
              paragraphTextEnd,
              Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
          }
        }

        val indent = paragraph.indentLeft.coerceAtLeast(0f)
        if (indent > 0.5f || paragraph.isTableRow) {
          builder.setSpan(
            LeadingMarginSpan.Standard(0, indent.toInt()),
            paragraphTextStart,
            maxOf(paragraphTextEnd, paragraphTextStart + 1).coerceAtMost(builder.length),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
          )
        }

        builder.append('\n')
        // Extra breathing room after headings and paragraphs with wide spacing.
        if (paragraph.headingLevel in 1..6 || paragraph.spaceAfter >= 12f) {
          builder.append('\n')
        }
        if (paragraph.isTableRow) {
          builder.setSpan(
            android.text.style.TypefaceSpan("monospace"),
            paragraphStart,
            builder.length.coerceAtLeast(paragraphStart + 1),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
          )
        }
      }
      if (builder.isEmpty()) builder.append("This document has no readable text.")

      val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = BASE_TEXT_SIZE
        color = if (dark) Color.LTGRAY else Color.BLACK
        isSubpixelText = true
        isLinearText = false
      }

      val geometry = model.geometry
      val width = (geometry.width - geometry.marginLeft - geometry.marginRight).toInt().coerceAtLeast(1)
      val staticLayout = StaticLayout.Builder
        .obtain(builder, 0, builder.length, paint, width)
        .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(false)
        .setLineSpacing(0f, 1.12f)
        .build()

      val contentHeight = geometry.height - geometry.marginTop - geometry.marginBottom
      val lineCount = staticLayout.lineCount
      val pageOfLine = IntArray(lineCount)
      val firstLines = ArrayList<Int>()
      firstLines.add(0)

      var page = 0
      var used = 0f
      for (line in 0 until lineCount) {
        val height =
          (staticLayout.getLineBottom(line) - staticLayout.getLineTop(line)).toFloat()
        if (used > 0f && used + height > contentHeight) {
          page += 1
          used = 0f
          firstLines.add(line)
        }
        pageOfLine[line] = page
        used += height
      }

      return DocxLayout(
        model = model,
        mode = mode,
        content = builder,
        layout = staticLayout,
        pageOfLine = pageOfLine,
        pageFirstLine = firstLines.toIntArray(),
        anchorOffsets = anchorOffsets,
        pageCount = (page + 1).coerceAtLeast(1),
      )
    }
  }
}
