package com.margelo.nitro.hboxpdf.editor

import android.graphics.Color
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject

/** Kinds of mark the native editor can hold. Stored in JSON as-is. */
internal object MarkKind {
  const val HIGHLIGHT = "highlight"
  const val INK = "ink"
  const val NOTE = "note"
  const val SIGNATURE = "signature"
  const val STAMP = "stamp"
}

/**
 * One edit on a page.
 *
 * All geometry is normalised to the page box (0..1) so marks survive zooming,
 * rotating and re-rasterising without recomputation.
 */
internal class PdfMark(
  var id: String = "",
  var kind: String = MarkKind.HIGHLIGHT,
  var page: Int = 1,
  var rect: RectF = RectF(),
  var color: Int = Color.YELLOW,
  /** Stroke width relative to the page width. */
  var width: Float = 0.004f,
  /** Flat normalised points for ink strokes and signatures. */
  var points: FloatArray = FloatArray(0),
  var text: String = "",
  /** Glyph used by stamps: check, cross, dot, box, line. */
  var glyph: String = "",
  /**
   * One box per text line the markup covers, normalised to the page. Highlight,
   * underline and strikeout marks are stored this way so a selection spanning
   * several lines keeps each line's own geometry.
   */
  var rects: MutableList<RectF> = mutableListOf(),
) {
  fun copy(): PdfMark = PdfMark(
    id = id,
    kind = kind,
    page = page,
    rect = RectF(rect),
    color = color,
    width = width,
    points = points.copyOf(),
    text = text,
    glyph = glyph,
    rects = rects.mapTo(mutableListOf()) { RectF(it) },
  )

  /** Union of [rects] (or [rect] when the mark has none). */
  fun bounds(): RectF {
    if (rects.isEmpty()) return RectF(rect)
    val union = RectF(rects.first())
    rects.forEach { union.union(it) }
    return union
  }

  fun isDraggable(): Boolean = kind == MarkKind.NOTE || kind == MarkKind.SIGNATURE || kind == MarkKind.STAMP

  fun hitRect(): RectF {
    val box = RectF(rect)
    if (kind == MarkKind.NOTE) {
      // Pins are drawn as a circle centred on the point, not as a rectangle.
      val half = 0.03f
      return RectF(box.centerX() - half, box.centerY() - half, box.centerX() + half, box.centerY() + half)
    }
    return box
  }
}

internal object PdfMarkCodec {
  /** Bumped when the stored shape changes; readers accept older versions. */
  const val VERSION = 1

  /**
   * Migration notes.
   *
   * v0 — sidecars written before multi-line highlights, and bare arrays passed
   *      to `decode`: a mark stored only its bounding `rect`, `rects` did not
   *      exist yet, and `glyph` meant the stamp glyph, so a markup mark had
   *      none.
   * v1 — current: `rects` holds one normalised box per selected line and a
   *      markup mark keeps its style in `glyph`
   *      ("highlight" | "underline" | "strikeout").
   *
   * A v0 mark therefore needs `rects` seeded from `rect` (so it draws and can
   * be restyled) and a default markup style, which is what [migrate] does.
   */
  private fun migrate(mark: PdfMark, version: Int) {
    if (version >= VERSION) return
    if (mark.rects.isEmpty() && !mark.rect.isEmpty) mark.rects.add(RectF(mark.rect))
    if (mark.kind == MarkKind.HIGHLIGHT && mark.glyph.isBlank()) mark.glyph = "highlight"
  }

  fun encode(marks: List<PdfMark>): String = encodeArray(marks).toString()

  /** The sidecar document that is written next to the PDF. */
  fun encodeDocument(documentPath: String, marks: List<PdfMark>): String {
    val root = JSONObject()
    root.put("version", VERSION)
    root.put("documentPath", documentPath)
    root.put("updatedAt", System.currentTimeMillis())
    root.put("marks", encodeArray(marks))
    return root.toString()
  }

  /** Accepts both the versioned document and a bare array of marks. */
  fun decodeDocument(raw: String?): MutableList<PdfMark> {
    if (raw.isNullOrBlank()) return mutableListOf()
    return try {
      val trimmed = raw.trim()
      if (trimmed.startsWith("{")) {
        val root = JSONObject(trimmed)
        val marks = root.optJSONArray("marks") ?: return mutableListOf()
        decodeArray(marks, root.optInt("version", 0))
      } else {
        decode(raw)
      }
    } catch (_: Throwable) {
      mutableListOf()
    }
  }

  private fun encodeArray(marks: List<PdfMark>): JSONArray {
    val array = JSONArray()
    marks.forEach { mark ->
      val item = JSONObject()
      item.put("id", mark.id)
      item.put("kind", mark.kind)
      item.put("page", mark.page)
      item.put("rect", JSONArray().apply {
        put(mark.rect.left.toDouble())
        put(mark.rect.top.toDouble())
        put(mark.rect.width().toDouble())
        put(mark.rect.height().toDouble())
      })
      item.put("color", hex(mark.color))
      item.put("width", mark.width.toDouble())
      item.put("points", JSONArray().apply { mark.points.forEach { put(it.toDouble()) } })
      item.put("text", mark.text)
      item.put("glyph", mark.glyph)
      item.put("rects", JSONArray().apply {
        mark.rects.forEach { box ->
          put(JSONArray().apply {
            put(box.left.toDouble())
            put(box.top.toDouble())
            put(box.width().toDouble())
            put(box.height().toDouble())
          })
        }
      })
      array.put(item)
    }
    return array
  }

  fun decode(raw: String?): MutableList<PdfMark> {
    if (raw.isNullOrBlank()) return mutableListOf()
    return try {
      decodeArray(JSONArray(raw), 0)
    } catch (_: Throwable) {
      mutableListOf()
    }
  }

  private fun decodeArray(array: JSONArray, version: Int): MutableList<PdfMark> {
    val result = mutableListOf<PdfMark>()
    try {
      for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val rectJson = item.optJSONArray("rect")
        val rect = RectF()
        if (rectJson != null && rectJson.length() >= 4) {
          val x = rectJson.optDouble(0, 0.0).toFloat()
          val y = rectJson.optDouble(1, 0.0).toFloat()
          rect.set(x, y, x + rectJson.optDouble(2, 0.0).toFloat(), y + rectJson.optDouble(3, 0.0).toFloat())
        }
        val pointsJson = item.optJSONArray("points")
        val points = if (pointsJson == null) FloatArray(0) else FloatArray(pointsJson.length()) {
          pointsJson.optDouble(it, 0.0).toFloat()
        }
        val rectsJson = item.optJSONArray("rects")
        val rects = mutableListOf<RectF>()
        if (rectsJson != null) {
          for (index2 in 0 until rectsJson.length()) {
            val entry = rectsJson.optJSONArray(index2) ?: continue
            if (entry.length() < 4) continue
            val x = entry.optDouble(0, 0.0).toFloat()
            val y = entry.optDouble(1, 0.0).toFloat()
            rects.add(RectF(x, y, x + entry.optDouble(2, 0.0).toFloat(), y + entry.optDouble(3, 0.0).toFloat()))
          }
        }
        val mark = PdfMark(
          id = item.optString("id"),
          kind = item.optString("kind", MarkKind.HIGHLIGHT),
          page = item.optInt("page", 1),
          rect = rect,
          color = parseColor(item.optString("color"), Color.YELLOW),
          width = item.optDouble("width", 0.004).toFloat(),
          points = points,
          text = item.optString("text"),
          glyph = item.optString("glyph"),
          rects = rects,
        )
        migrate(mark, version)
        result.add(mark)
      }
    } catch (_: Throwable) {
      // A malformed list must not take the editor down; start empty instead.
    }
    return result
  }

  fun hex(color: Int): String = String.format("#%08X", color)

  private fun parseColor(value: String, fallback: Int): Int = try {
    if (value.isBlank()) fallback else Color.parseColor(value)
  } catch (_: Throwable) {
    fallback
  }
}
