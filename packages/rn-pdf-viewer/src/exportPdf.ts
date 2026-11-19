import {
  PDFArray,
  PDFDocument,
  PDFName,
  PDFNumber,
  PDFPage,
  StandardFonts,
  rgb,
} from 'pdf-lib'
import { readRect } from './editor'
import type { PdfAnnotation, PdfRect } from './editor'

/**
 * Writing the edits back into the PDF.
 *
 * Two outputs, because they answer different questions:
 *
 * - `annotations` (default) appends real `/Highlight`, `/Underline`,
 *   `/StrikeOut` and `/Ink` dictionaries, so the recipient can still edit or
 *   remove the markup in Acrobat, Preview or any conforming viewer.
 * - `flatten` paints the same marks into the page content, so the file looks
 *   identical even in viewers that ignore annotations. Lossy: the markup can no
 *   longer be edited.
 *
 * Marks are stored normalised to the rendered page box, so every box is mapped
 * back through the page's `/Rotate` and its media box before it is written.
 */

export interface MarkedPdfOptions {
  /** Paint into the page content instead of adding annotation dictionaries. */
  flatten?: boolean
}

interface Rgba {
  r: number
  g: number
  b: number
  a: number
}

/** Native colours are `#AARRGGBB`; hand-written ones are often `#RRGGBB`. */
export function parseArgb(value: string | undefined, fallback = '#FFCC00'): Rgba {
  const raw = (value ?? fallback).trim().replace(/^#/, '')
  const padded = raw.length === 8 ? raw : raw.length === 6 ? `FF${raw}` : 'FFFFCC00'
  const int = Number.parseInt(padded, 16)
  if (Number.isNaN(int)) return { r: 1, g: 0.8, b: 0, a: 1 }
  return {
    a: ((int >>> 24) & 0xff) / 255,
    r: ((int >>> 16) & 0xff) / 255,
    g: ((int >>> 8) & 0xff) / 255,
    b: (int & 0xff) / 255,
  }
}

function normaliseRotation(page: PDFPage): 0 | 90 | 180 | 270 {
  const angle = ((page.getRotation().angle % 360) + 360) % 360
  return angle === 90 || angle === 180 || angle === 270 ? angle : 0
}

interface PageSpace {
  /** Unrotated page size, the space annotations and content are written in. */
  width: number
  height: number
  /** Size the editor worked in, i.e. after /Rotate. */
  displayWidth: number
  displayHeight: number
  rotation: 0 | 90 | 180 | 270
}

function pageSpace(page: PDFPage): PageSpace {
  const { width, height } = page.getSize()
  const rotation = normaliseRotation(page)
  const swapped = rotation === 90 || rotation === 270
  return {
    width,
    height,
    rotation,
    displayWidth: swapped ? height : width,
    displayHeight: swapped ? width : height,
  }
}

/**
 * A normalised (0..1, top-left) box in the rendered page becomes a PDF rect in
 * the page's own bottom-left coordinate system.
 */
export function normalisedRectToPdf(box: PdfRect, space: PageSpace): {
  x: number
  y: number
  width: number
  height: number
} {
  const x1 = box.x * space.displayWidth
  const x2 = (box.x + box.width) * space.displayWidth
  const y1 = box.y * space.displayHeight
  const y2 = (box.y + box.height) * space.displayHeight

  // Undo the page rotation: display (y down) back to unrotated (y down).
  let left: number
  let right: number
  let top: number
  let bottom: number
  switch (space.rotation) {
    case 90:
      left = y1
      right = y2
      top = space.displayWidth - x2
      bottom = space.displayWidth - x1
      break
    case 180:
      left = space.displayWidth - x2
      right = space.displayWidth - x1
      top = space.displayHeight - y2
      bottom = space.displayHeight - y1
      break
    case 270:
      left = space.displayHeight - y2
      right = space.displayHeight - y1
      top = x1
      bottom = x2
      break
    default:
      left = x1
      right = x2
      top = y1
      bottom = y2
      break
  }

  // Unrotated space is y down; PDF is y up.
  return {
    x: left,
    y: space.height - bottom,
    width: Math.max(0.5, right - left),
    height: Math.max(0.5, bottom - top),
  }
}

function rectsOf(annotation: PdfAnnotation): PdfRect[] {
  if (annotation.rects && annotation.rects.length > 0) return annotation.rects.map(readRect)
  if (annotation.rect) return [readRect(annotation.rect)]
  return []
}

function pointsOf(annotation: PdfAnnotation): number[] {
  const flat: number[] = []
  if (annotation.points) {
    for (const value of annotation.points) flat.push(value)
    return flat
  }
  if (annotation.strokes) {
    for (const stroke of annotation.strokes) {
      for (const point of stroke) flat.push(point.x, point.y)
    }
  }
  return flat
}

function subtypeFor(annotation: PdfAnnotation): string {
  if (annotation.kind !== 'highlight') return 'Ink'
  switch (annotation.glyph) {
    case 'underline':
      return 'Underline'
    case 'strikeout':
      return 'StrikeOut'
    default:
      return 'Highlight'
  }
}

/**
 * Adds annotation dictionaries. Returns the number of marks that could be
 * written; unsupported kinds (signatures, stamps) are reported by the caller.
 */
function writeAnnotations(doc: PDFDocument, annotations: PdfAnnotation[]): number {
  const pages = doc.getPages()
  const context = doc.context
  let written = 0

  for (const annotation of annotations) {
    const page = pages[annotation.page - 1]
    if (!page) continue
    const space = pageSpace(page)
    const colour = parseArgb(annotation.color)
    const boxes = rectsOf(annotation).map((box) => normalisedRectToPdf(box, space))

    if (annotation.kind === 'highlight' && boxes.length > 0) {
      const quads: number[] = []
      let left = Infinity
      let bottom = Infinity
      let right = -Infinity
      let top = -Infinity
      for (const box of boxes) {
        const x1 = box.x
        const x2 = box.x + box.width
        const y1 = box.y
        const y2 = box.y + box.height
        // Upper-left, upper-right, lower-left, lower-right.
        quads.push(x1, y2, x2, y2, x1, y1, x2, y1)
        left = Math.min(left, x1)
        bottom = Math.min(bottom, y1)
        right = Math.max(right, x2)
        top = Math.max(top, y2)
      }
      const dict = context.obj({
        Type: 'Annot',
        Subtype: subtypeFor(annotation),
        Rect: [left, bottom, right, top],
        QuadPoints: quads,
        C: [colour.r, colour.g, colour.b],
        CA: annotation.glyph === 'highlight' || !annotation.glyph ? colour.a : 1,
        F: 4,
        P: page.ref,
      })
      appendAnnotation(context, page, context.register(dict))
      written++
      continue
    }

    if (annotation.kind === 'ink') {
      const flat = pointsOf(annotation)
      const strokes: number[][] = []
      let width = 0
      for (let i = 0; i + 1 < flat.length; i += 2) {
        const nx = flat[i]!
        const ny = flat[i + 1]!
        const box: PdfRect = { x: nx, y: ny, width: 0, height: 0 }
        const mapped = normalisedRectToPdf(box, space)
        strokes.push([mapped.x, mapped.y])
        width = Math.max(width, mapped.x)
      }
      if (strokes.length < 2) continue
      let left = Infinity
      let bottom = Infinity
      let right = -Infinity
      let top = -Infinity
      for (const [x, y] of strokes) {
        left = Math.min(left, x)
        bottom = Math.min(bottom, y)
        right = Math.max(right, x)
        top = Math.max(top, y)
      }
      const thickness = Math.max(1, (annotation.strokeWidth ?? annotation.width ?? 0.004) * space.displayWidth)
      const dict = context.obj({
        Type: 'Annot',
        Subtype: 'Ink',
        Rect: [left - thickness, bottom - thickness, right + thickness, top + thickness],
        InkList: [strokes.flat()],
        C: [colour.r, colour.g, colour.b],
        CA: colour.a,
        BS: { W: thickness },
        F: 4,
        P: page.ref,
      })
      appendAnnotation(context, page, context.register(dict))
      written++
      continue
    }

    if (annotation.kind === 'signature') {
      const box = annotation.rect ? readRect(annotation.rect) : null
      const flat = pointsOf(annotation)
      if (!box || flat.length < 4) continue
      // Signature points are relative to the mark's own box, so each one is
      // placed inside the box before it is mapped onto the page.
      const strokes: number[][] = []
      for (let i = 0; i + 1 < flat.length; i += 2) {
        const inner = {
          x: box.x + flat[i]! * box.width,
          y: box.y + flat[i + 1]! * box.height,
          width: 0,
          height: 0,
        }
        const mapped = normalisedRectToPdf(inner, space)
        strokes.push([mapped.x, mapped.y])
      }
      if (strokes.length < 2) continue
      const boxPdf = normalisedRectToPdf(box, space)
      const thickness = Math.max(1, (annotation.width ?? 0.004) * boxPdf.width * 8)
      const dict = context.obj({
        Type: 'Annot',
        Subtype: 'Ink',
        Rect: [boxPdf.x, boxPdf.y, boxPdf.x + boxPdf.width, boxPdf.y + boxPdf.height],
        InkList: [strokes.flat()],
        C: [colour.r, colour.g, colour.b],
        CA: 1,
        BS: { W: thickness },
        F: 4,
        P: page.ref,
      })
      appendAnnotation(context, page, context.register(dict))
      written++
      continue
    }

    if (annotation.kind === 'stamp' && annotation.rect) {
      const box = normalisedRectToPdf(readRect(annotation.rect), space)
      const label = annotation.text ?? ''
      if (!label) continue
      const dict = context.obj({
        Type: 'Annot',
        Subtype: 'FreeText',
        Rect: [box.x, box.y, box.x + box.width, box.y + box.height],
        Contents: label,
        DA: '/Helv 14 Tf 0 g',
        C: [colour.r, colour.g, colour.b],
        F: 4,
        P: page.ref,
      })
      appendAnnotation(context, page, context.register(dict))
      written++
      continue
    }

    if (annotation.kind === 'note' && annotation.point) {
      const mapped = normalisedRectToPdf(
        { x: annotation.point.x, y: annotation.point.y, width: 0, height: 0 },
        space,
      )
      const dict = context.obj({
        Type: 'Annot',
        Subtype: 'Text',
        Rect: [mapped.x, mapped.y, mapped.x + 20, mapped.y + 20],
        Contents: annotation.text ?? '',
        Name: 'Comment',
        C: [colour.r, colour.g, colour.b],
        F: 4,
        P: page.ref,
      })
      appendAnnotation(context, page, context.register(dict))
      written++
    }
  }
  return written
}

function appendAnnotation(
  context: PDFDocument['context'],
  page: PDFPage,
  ref: ReturnType<PDFDocument['context']['register']>,
): void {
  const existing = page.node.lookupMaybe(PDFName.of('Annots'), PDFArray)
  if (existing) {
    existing.push(ref as never)
    return
  }
  page.node.set(PDFName.of('Annots'), context.obj([ref]))
}

/** Paints marks into the page content, for viewers that ignore annotations. */
async function writeFlattened(doc: PDFDocument, annotations: PdfAnnotation[]): Promise<number> {
  const pages = doc.getPages()
  let written = 0

  for (const stroke of annotations) {
    const page = pages[stroke.page - 1]
    if (!page) continue
    const space = pageSpace(page)
    const colour = parseArgb(stroke.color)
    const boxes = rectsOf(stroke).map((box) => normalisedRectToPdf(box, space))
    const tint = rgb(colour.r, colour.g, colour.b)
    const opacity = Math.max(0.05, Math.min(1, colour.a))

    if (stroke.kind === 'highlight') {
      for (const box of boxes) {
        if (stroke.glyph === 'underline') {
          page.drawLine({
            start: { x: box.x, y: box.y },
            end: { x: box.x + box.width, y: box.y },
            thickness: Math.max(1, space.displayWidth * 0.0025),
            color: tint,
            opacity,
          })
        } else if (stroke.glyph === 'strikeout') {
          const middle = box.y + box.height / 2
          page.drawLine({
            start: { x: box.x, y: middle },
            end: { x: box.x + box.width, y: middle },
            thickness: Math.max(1, space.displayWidth * 0.0025),
            color: tint,
            opacity,
          })
        } else {
          page.drawRectangle({
            x: box.x,
            y: box.y,
            width: box.width,
            height: box.height,
            color: tint,
            opacity,
          })
        }
      }
      written++
      continue
    }

    if (stroke.kind === 'signature') {
      const box = stroke.rect ? readRect(stroke.rect) : null
      const flat = pointsOf(stroke)
      if (!box || flat.length < 4) continue
      const boxPdf = normalisedRectToPdf(box, space)
      const thickness = Math.max(1, (stroke.width ?? 0.004) * boxPdf.width * 8)
      let previous: { x: number; y: number } | null = null
      for (let i = 0; i + 1 < flat.length; i += 2) {
        const mapped = normalisedRectToPdf(
          {
            x: box.x + flat[i]! * box.width,
            y: box.y + flat[i + 1]! * box.height,
            width: 0,
            height: 0,
          },
          space,
        )
        const point = { x: mapped.x, y: mapped.y }
        if (previous) {
          page.drawLine({ start: previous, end: point, thickness, color: tint, opacity })
        }
        previous = point
      }
      written++
      continue
    }

    if (stroke.kind === 'stamp' && stroke.rect) {
      const box = normalisedRectToPdf(readRect(stroke.rect), space)
      const label = stroke.text ?? ''
      if (!label) continue
      const font = await doc.embedFont(StandardFonts.HelveticaBold)
      page.drawText(label, {
        x: box.x,
        y: box.y + box.height / 2 - box.height * 0.3,
        size: Math.max(6, box.height * 0.8),
        font,
        color: tint,
        opacity,
      })
      written++
      continue
    }

    if (stroke.kind === 'ink') {
      const flat = pointsOf(stroke)
      const thickness = Math.max(1, (stroke.strokeWidth ?? stroke.width ?? 0.004) * space.displayWidth)
      let previous: { x: number; y: number } | null = null
      for (let i = 0; i + 1 < flat.length; i += 2) {
        const mapped = normalisedRectToPdf(
          { x: flat[i]!, y: flat[i + 1]!, width: 0, height: 0 },
          space,
        )
        const point = { x: mapped.x, y: mapped.y }
        if (previous) {
          page.drawLine({
            start: previous,
            end: point,
            thickness,
            color: tint,
            opacity: Math.max(0.05, Math.min(1, colour.a)),
          })
        }
        previous = point
      }
      written++
    }
  }
  return written
}

/**
 * Returns a new copy of `source` with `annotations` written in. The original
 * bytes are never modified.
 */
export async function buildMarkedPdf(
  source: Uint8Array,
  annotations: PdfAnnotation[],
  options: MarkedPdfOptions = {},
): Promise<Uint8Array> {
  const doc = await PDFDocument.load(source, { ignoreEncryption: true })
  // Markup stays as it was written; the host shares this copy, it is not a form
  // that needs re-saving on every open.
  if (options.flatten) {
    await writeFlattened(doc, annotations)
  } else {
    writeAnnotations(doc, annotations)
  }
  return doc.save()
}

/** Marks the exporter cannot represent as annotations on its own. */
export function unsupportedKinds(annotations: PdfAnnotation[]): string[] {
  const kinds = new Set<string>()
  for (const annotation of annotations) {
    if (annotation.kind === 'signature' || annotation.kind === 'stamp') kinds.add(annotation.kind)
  }
  return [...kinds]
}
