package com.margelo.nitro.hboxpdf.editor

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Pure geometry for text selection and ink strokes.
 *
 * Nothing in here touches Android: the selection surface hands in flat
 * `[left, top, right, bottom]` boxes and flat `[x, y]` points, which is what
 * makes the tricky parts — char hit testing, line merging, point thinning —
 * testable on the JVM instead of only on a device.
 */
internal object PdfMarkGeometry {

  /** Number of boxes packed in [boxes]. */
  fun count(boxes: FloatArray): Int = boxes.size / 4

  fun emptyCount(boxes: FloatArray, index: Int): Boolean {
    val base = index * 4
    return boxes[base + 2] <= boxes[base] || boxes[base + 3] <= boxes[base + 1]
  }

  /**
   * Nearest character to a point in page points.
   *
   * Inside or almost touching a glyph wins (`insideSlop`); otherwise the
   * closest glyph within `fallbackSlop` of its centre is used, so a sloppy tap
   * still starts a selection on the right line.
   */
  fun charIndexAt(
    boxes: FloatArray,
    px: Float,
    py: Float,
    insideSlop: Float,
    fallbackSlop: Float,
  ): Int {
    val total = count(boxes)
    if (total == 0) return -1
    var best = -1
    var bestDistance = Float.MAX_VALUE
    var fallback = -1
    var fallbackDistance = Float.MAX_VALUE
    for (i in 0 until total) {
      if (emptyCount(boxes, i)) continue
      val base = i * 4
      val left = boxes[base]
      val top = boxes[base + 1]
      val right = boxes[base + 2]
      val bottom = boxes[base + 3]
      val dx = when {
        px < left -> left - px
        px > right -> px - right
        else -> 0f
      }
      val dy = when {
        py < top -> top - py
        py > bottom -> py - bottom
        else -> 0f
      }
      val distance = dx * dx + dy * dy
      if (distance < bestDistance) {
        bestDistance = distance
        best = i
      }
      val centreX = (left + right) / 2f
      val centreY = (top + bottom) / 2f
      val centre = hypot(centreX - px, centreY - py)
      if (centre < fallbackDistance) {
        fallbackDistance = centre
        fallback = i
      }
    }
    if (best >= 0 && bestDistance <= insideSlop * insideSlop) return best
    return if (fallback >= 0 && fallbackDistance <= fallbackSlop) fallback else -1
  }

  /** True when the two boxes sit on the same text line. */
  fun sameLine(boxes: FloatArray, a: Int, b: Int): Boolean {
    if (emptyCount(boxes, a) || emptyCount(boxes, b)) return false
    val overlap = min(boxes[a * 4 + 3], boxes[b * 4 + 3]) - max(boxes[a * 4 + 1], boxes[b * 4 + 1])
    val shorter = min(
      boxes[a * 4 + 3] - boxes[a * 4 + 1],
      boxes[b * 4 + 3] - boxes[b * 4 + 1],
    )
    return overlap > shorter * 0.4f
  }

  /**
   * One box per selected line between two character indices, in the same space
   * as the input. Boxes on the same line are merged, which is what makes a
   * multi-line highlight look like a band and not like per-glyph tiling.
   */
  fun selectionRects(boxes: FloatArray, from: Int, to: Int): FloatArray {
    val total = count(boxes)
    if (total == 0) return FloatArray(0)
    val start = min(from, to).coerceIn(0, total - 1)
    val end = max(from, to).coerceIn(0, total - 1)
    val lines = ArrayList<FloatArray>(8)
    for (i in start..end) {
      if (emptyCount(boxes, i)) continue
      val base = i * 4
      var left = boxes[base]
      val top = boxes[base + 1]
      var right = boxes[base + 2]
      var bottom = boxes[base + 3]
      var merged = false
      for (line in lines) {
        val overlap = min(line[3], bottom) - max(line[1], top)
        if (overlap > (bottom - top) * 0.45f) {
          line[0] = min(line[0], left)
          line[1] = min(line[1], top)
          line[2] = max(line[2], right)
          line[3] = max(line[3], bottom)
          merged = true
          break
        }
      }
      if (!merged) lines.add(floatArrayOf(left, top, right, bottom))
    }
    val out = FloatArray(lines.size * 4)
    lines.forEachIndexed { index, line ->
      out[index * 4] = line[0]
      out[index * 4 + 1] = line[1]
      out[index * 4 + 2] = line[2]
      out[index * 4 + 3] = line[3]
    }
    return out
  }

  /**
   * Ramer–Douglas–Peucker over a flat `[x, y]` stroke. Endpoints are always
   * kept; a stroke that is already short is returned untouched.
   */
  fun simplify(points: FloatArray, tolerance: Float): FloatArray {
    val total = points.size / 2
    if (total <= 3) return points
    val keep = BooleanArray(total)
    keep[0] = true
    keep[total - 1] = true
    val stack = ArrayList<IntArray>(16)
    stack.add(intArrayOf(0, total - 1))
    while (stack.isNotEmpty()) {
      val range = stack.removeAt(stack.size - 1)
      val first = range[0]
      val last = range[1]
      if (last <= first + 1) continue
      val ax = points[first * 2]
      val ay = points[first * 2 + 1]
      val bx = points[last * 2]
      val by = points[last * 2 + 1]
      val dx = bx - ax
      val dy = by - ay
      val length = hypot(dx, dy)
      var worst = -1f
      var worstIndex = -1
      for (i in first + 1 until last) {
        val px = points[i * 2] - ax
        val py = points[i * 2 + 1] - ay
        val distance = if (length < 0.000001f) {
          hypot(px, py)
        } else {
          abs(dx * py - dy * px) / length
        }
        if (distance > worst) {
          worst = distance
          worstIndex = i
        }
      }
      if (worstIndex > 0 && worst > tolerance) {
        keep[worstIndex] = true
        stack.add(intArrayOf(first, worstIndex))
        stack.add(intArrayOf(worstIndex, last))
      }
    }
    var kept = 0
    for (i in 0 until total) if (keep[i]) kept++
    val out = FloatArray(kept * 2)
    var cursor = 0
    for (i in 0 until total) {
      if (!keep[i]) continue
      out[cursor++] = points[i * 2]
      out[cursor++] = points[i * 2 + 1]
    }
    return out
  }
}
