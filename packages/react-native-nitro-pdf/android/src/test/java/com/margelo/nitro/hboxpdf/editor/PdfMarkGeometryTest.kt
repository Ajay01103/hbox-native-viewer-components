package com.margelo.nitro.hboxpdf.editor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The selection and ink maths that used to be reachable only through a device
 * drag: synthetic char boxes make the failure modes (a pointer between glyphs,
 * a multi-line range, a jittery stroke) reproducible on the JVM.
 */
class PdfMarkGeometryTest {

  /** Three characters on one line, page points. */
  private fun line(top: Float = 0f, height: Float = 10f): FloatArray = floatArrayOf(
    0f, top, 5f, top + height,
    5f, top, 10f, top + height,
    10f, top, 15f, top + height,
  )

  private fun twoLines(): FloatArray = floatArrayOf(
    // "ab"
    0f, 0f, 5f, 10f,
    5f, 0f, 10f, 10f,
    // "cd" on the next line
    0f, 20f, 5f, 30f,
    5f, 20f, 10f, 30f,
  )

  @Test
  fun charIndexAtReturnsTheGlyphUnderThePoint() {
    val boxes = line()
    assertEquals(0, PdfMarkGeometry.charIndexAt(boxes, 2f, 5f, 1f, 4f))
    assertEquals(1, PdfMarkGeometry.charIndexAt(boxes, 6f, 5f, 1f, 4f))
    assertEquals(2, PdfMarkGeometry.charIndexAt(boxes, 14f, 9f, 1f, 4f))
  }

  @Test
  fun charIndexAtSnapsToTheNearestLineWithinTheFallback() {
    val boxes = twoLines()
    // Between the two lines: the centre of line one is 5pt away, line two 15pt.
    assertEquals(0, PdfMarkGeometry.charIndexAt(boxes, 2f, 15f, 1f, 12f))
    // Nothing within reach: no selection rather than a wrong one.
    assertEquals(-1, PdfMarkGeometry.charIndexAt(boxes, 400f, 400f, 1f, 12f))
  }

  @Test
  fun charIndexAtOnAnEmptyPageIsMinusOne() {
    assertEquals(-1, PdfMarkGeometry.charIndexAt(FloatArray(0), 1f, 1f, 1f, 4f))
  }

  @Test
  fun selectionRectsMergesEachLineIntoOneBand() {
    val rects = PdfMarkGeometry.selectionRects(twoLines(), 0, 3)
    assertEquals(8, rects.size)
    assertArrayEquals(floatArrayOf(0f, 0f, 10f, 10f), rects.copyOfRange(0, 4), 0.001f)
    assertArrayEquals(floatArrayOf(0f, 20f, 10f, 30f), rects.copyOfRange(4, 8), 0.001f)
  }

  @Test
  fun selectionRectsAcceptsAReversedRange() {
    val forward = PdfMarkGeometry.selectionRects(twoLines(), 0, 2)
    val backward = PdfMarkGeometry.selectionRects(twoLines(), 2, 0)
    assertArrayEquals(forward, backward, 0.001f)
  }

  @Test
  fun aSingleLineSelectionProducesOneBox() {
    val rects = PdfMarkGeometry.selectionRects(line(), 1, 2)
    assertArrayEquals(floatArrayOf(5f, 0f, 15f, 10f), rects, 0.001f)
  }

  @Test
  fun sameLineIgnoresGlyphsOnOtherLines() {
    val boxes = twoLines()
    assertTrue(PdfMarkGeometry.sameLine(boxes, 0, 1))
    assertTrue(!PdfMarkGeometry.sameLine(boxes, 0, 2))
  }

  @Test
  fun simplifyCollapsesANearlyStraightStrokeToItsEnds() {
    // Six samples on an almost straight line: every interior point is within
    // 0.8 of the chord, so thinning leaves the endpoints alone.
    val points = floatArrayOf(
      0f, 0f,
      1f, 0f,
      2f, 0f,
      3f, 0f,
      4f, 0f,
      5f, 1f,
    )
    val simplified = PdfMarkGeometry.simplify(points, 0.8f)
    assertEquals(2, simplified.size / 2)
    assertEquals(0f, simplified[0], 0.001f)
    assertEquals(5f, simplified[2], 0.001f)
    assertEquals(1f, simplified[3], 0.001f)
  }

  @Test
  fun simplifyKeepsASpike() {
    val points = floatArrayOf(0f, 0f, 1f, 0f, 2f, 5f, 3f, 0f, 4f, 0f)
    val simplified = PdfMarkGeometry.simplify(points, 0.5f)
    // Thinning must never flatten a corner, even when that keeps every sample.
    val ys = (1 until simplified.size / 2).map { simplified[it * 2 + 1] }
    assertTrue(ys.any { it == 5f })
    assertEquals(0f, simplified[0], 0.001f)
    assertEquals(4f, simplified[simplified.size - 2], 0.001f)
  }

  @Test
  fun simplifyLeavesShortStrokesAlone() {
    val points = floatArrayOf(0f, 0f, 1f, 1f, 2f, 2f)
    assertArrayEquals(points, PdfMarkGeometry.simplify(points, 10f), 0.001f)
  }

  @Test
  fun simplifyAlwaysKeepsTheEndpoints() {
    val points = floatArrayOf(0f, 0f, 5f, 0f, 10f, 0f, 15f, 0f)
    val simplified = PdfMarkGeometry.simplify(points, 100f)
    assertEquals(0f, simplified[0], 0.001f)
    assertEquals(15f, simplified[simplified.size - 2], 0.001f)
  }
}
