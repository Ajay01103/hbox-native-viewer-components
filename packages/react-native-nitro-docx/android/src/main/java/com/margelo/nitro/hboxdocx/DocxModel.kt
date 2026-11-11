package com.margelo.nitro.hboxdocx

import android.graphics.Color

/** A single styled run of text inside a paragraph. */
internal data class DocxRun(
  val text: String,
  val bold: Boolean = false,
  val italic: Boolean = false,
  val underline: Boolean = false,
  val strike: Boolean = false,
  val color: Int? = null,
  /** Point size from `w:sz` (half-points); 0 means "inherit". */
  val sizePt: Float = 0f,
  val highlight: Int? = null,
  /** `insert` / `delete` for tracked changes, empty otherwise. */
  val revision: String = "",
  val commentIds: List<String> = emptyList(),
)

internal data class DocxParagraph(
  val anchorId: String,
  val runs: List<DocxRun>,
  val headingLevel: Int = 0,
  /** -1 when the paragraph is not part of a numbered/bulleted list. */
  val listLevel: Int = -1,
  val marker: String = "",
  val alignment: String = "left",
  val spaceBefore: Float = 0f,
  val spaceAfter: Float = 4f,
  val indentLeft: Float = 0f,
  val pageBreakBefore: Boolean = false,
  val isTableRow: Boolean = false,
) {
  val plainText: String
    get() = runs.joinToString("") { it.text }
}

internal data class DocxCommentData(
  val id: String,
  val author: String,
  val date: String,
  val text: String,
)

internal data class DocxOutlineEntry(
  val id: String,
  val title: String,
  val level: Int,
)

/** Page geometry in points (1/72 inch), the same space the layout uses. */
internal data class DocxPageGeometry(
  val width: Float,
  val height: Float,
  val marginLeft: Float,
  val marginRight: Float,
  val marginTop: Float,
  val marginBottom: Float,
)

internal class DocxModel(
  val fileName: String,
  val title: String,
  val geometry: DocxPageGeometry,
  val paragraphs: List<DocxParagraph>,
  val comments: List<DocxCommentData>,
  val outline: List<DocxOutlineEntry>,
  val header: String,
  val footer: String,
  val hasTrackedChanges: Boolean,
  val warnings: List<String>,
)

/** `#rrggbb`, `#aarrggbb` and `rgb()/rgba()` are accepted from JS themes. */
internal fun parseDocxColor(value: String?, fallback: Int): Int {
  if (value.isNullOrBlank()) return fallback
  val trimmed = value.trim()
  if (trimmed.startsWith("rgb", ignoreCase = true)) {
    val body = trimmed.substringAfter('(', "").substringBefore(')')
    val parts = body.split(',').map { it.trim() }
    if (parts.size >= 3) {
      val red = parts[0].toIntOrNull() ?: return fallback
      val green = parts[1].toIntOrNull() ?: return fallback
      val blue = parts[2].toIntOrNull() ?: return fallback
      val alpha = if (parts.size >= 4) {
        val raw = parts[3].toFloatOrNull() ?: 1f
        (raw.coerceIn(0f, 1f) * 255f).toInt()
      } else {
        255
      }
      return Color.argb(alpha, red, green, blue)
    }
    return fallback
  }
  return try {
    Color.parseColor(trimmed)
  } catch (_: Exception) {
    fallback
  }
}
