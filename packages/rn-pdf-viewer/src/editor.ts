import { useCallback, useRef, useState } from 'react'

export type PdfTool = 'none' | 'highlight' | 'draw' | 'comment' | 'sign'

export interface PdfPoint {
  x: number
  y: number
}

export interface PdfRect {
  x: number
  y: number
  width: number
  height: number
}

/** `[x, y, width, height]`, the shape the native editor emits. */
export type PdfRectTuple = [number, number, number, number]

export type PdfMarkKind = 'highlight' | 'ink' | 'note' | 'signature' | 'stamp'

/**
 * One mark on one page. Geometry is normalised to the rendered page box
 * (0..1), so marks stay attached to the same text at any zoom level, rotation
 * or screen size.
 *
 * The native editor serialises rects as `[x, y, width, height]` tuples and ink
 * as a flat `points` array; hand-built marks may use the object forms instead,
 * so both are accepted everywhere a mark is read.
 */
export interface PdfAnnotation {
  id: string
  page: number
  kind: PdfMarkKind
  /** `#AARRGGBB` from the native editor, `#RRGGBB` from hand-built marks. */
  color: string
  /** Markup style: `highlight`, `underline` or `strikeout`. */
  glyph?: string
  /** One box per text line for text markup. */
  rects?: (PdfRect | PdfRectTuple)[]
  /** Flat x,y pairs normalised to the page, as emitted natively. */
  points?: number[]
  strokes?: PdfPoint[][]
  /** Stroke width relative to the page width. */
  width?: number
  strokeWidth?: number
  point?: PdfPoint
  text?: string
  /** Placement box for a signature or stamp. */
  rect?: PdfRect | PdfRectTuple
}

export const HIGHLIGHT_COLORS = ['#ffd400', '#7ee787', '#79c0ff', '#ffa198'] as const

export const INK_COLORS = ['#111827', '#dc2626', '#2563eb', '#16a34a'] as const

/** Stroke widths in page pixels for the three size stops. */
export const INK_WIDTHS = [2, 4, 7] as const

/** Minimum highlight band height (fraction of the page) per size stop. */
export const HIGHLIGHT_HEIGHTS = [0.014, 0.026, 0.042] as const

export type NewAnnotation = Omit<PdfAnnotation, 'id' | 'color'> & { color?: string }

export interface UsePdfEditorOptions {
  initial?: PdfAnnotation[]
  onChange?: (annotations: PdfAnnotation[]) => void
}

export interface UsePdfEditorResult {
  annotations: PdfAnnotation[]
  tool: PdfTool
  color: string
  strokeWidth: number
  setTool(tool: PdfTool): void
  setColor(color: string): void
  setStrokeWidth(width: number): void
  add(annotation: NewAnnotation): void
  remove(id: string): void
  undo(): void
  clear(): void
  toJSON(): string
}

let nextAnnotationId = 1

/** Annotation state for the viewer/editor package. Lives in JS: no native write path needed. */
export function usePdfEditor({ initial, onChange }: UsePdfEditorOptions = {}): UsePdfEditorResult {
  const [annotations, setAnnotations] = useState<PdfAnnotation[]>(() => initial ?? [])
  const [tool, setTool] = useState<PdfTool>('none')
  const [color, setColor] = useState<string>(HIGHLIGHT_COLORS[0])
  const [strokeWidth, setStrokeWidth] = useState(3)
  const onChangeRef = useRef(onChange)
  onChangeRef.current = onChange

  const commit = useCallback((next: PdfAnnotation[]) => {
    setAnnotations(next)
    onChangeRef.current?.(next)
  }, [])

  const add = useCallback(
    (annotation: NewAnnotation) => {
      setAnnotations((current) => {
        const created: PdfAnnotation = {
          ...annotation,
          id: `a${nextAnnotationId++}`,
          color: annotation.color ?? color,
        }
        const next = [...current, created]
        onChangeRef.current?.(next)
        return next
      })
    },
    [color],
  )

  const remove = useCallback((id: string) => {
    setAnnotations((current) => {
      const next = current.filter((annotation) => annotation.id !== id)
      if (next.length === current.length) return current
      onChangeRef.current?.(next)
      return next
    })
  }, [])

  const undo = useCallback(() => {
    setAnnotations((current) => {
      if (current.length === 0) return current
      const next = current.slice(0, -1)
      onChangeRef.current?.(next)
      return next
    })
  }, [])

  const clear = useCallback(() => commit([]), [commit])

  const toJSON = useCallback(() => JSON.stringify(annotations), [annotations])

  return {
    annotations,
    tool,
    color,
    strokeWidth,
    setTool,
    setColor,
    setStrokeWidth,
    add,
    remove,
    undo,
    clear,
    toJSON,
  }
}

/** Version of the mark payload this package writes and understands. */
export const ANNOTATION_SCHEMA_VERSION = 1

/** The sidecar document the native editor can persist next to a PDF. */
export interface AnnotationDocument {
  version: number
  documentPath?: string
  updatedAt?: number
  marks: PdfAnnotation[]
}

/**
 * Upgrades a mark written by an older build.
 *
 * Highlights used to be stored as flat `points` quads (`left, top, right,
 * bottom` per line) with no `rects` and no `glyph`; both are normalised here so
 * older saved markup loads with the current model.
 */
export function migrateAnnotation(mark: PdfAnnotation): PdfAnnotation {
  if (mark.kind !== 'highlight') return mark
  if (mark.rects && mark.rects.length > 0) {
    return { ...mark, glyph: mark.glyph ?? 'highlight' }
  }
  const rects: PdfRectTuple[] = []
  const points = mark.points ?? []
  for (let i = 0; i + 3 < points.length; i += 4) {
    const left = points[i]!
    const top = points[i + 1]!
    const width = points[i + 2]! - left
    const height = points[i + 3]! - top
    if (width > 0 && height > 0) rects.push([left, top, width, height])
  }
  if (rects.length === 0 && mark.rect) {
    const box = readRect(mark.rect)
    rects.push([box.x, box.y, box.width, box.height])
  }
  return { ...mark, rects, glyph: mark.glyph ?? 'highlight' }
}

/**
 * Reads marks from either shape the editor can produce: the bare JSON array it
 * emits today, or the versioned sidecar document. Unknown versions are still
 * read — marks are additive, so a newer file degrades rather than fails.
 */
export function decodeAnnotations(
  payload: string | PdfAnnotation[] | AnnotationDocument | null | undefined,
): PdfAnnotation[] {
  if (!payload) return []
  let parsed: unknown = payload
  if (typeof payload === 'string') {
    try {
      parsed = JSON.parse(payload)
    } catch {
      return []
    }
  }
  const list = Array.isArray(parsed)
    ? parsed
    : parsed && typeof parsed === 'object' && Array.isArray((parsed as AnnotationDocument).marks)
      ? (parsed as AnnotationDocument).marks
      : []
  return (list as PdfAnnotation[])
    .filter((mark) => mark && typeof mark.id === 'string' && typeof mark.page === 'number')
    .map(migrateAnnotation)
}

/** Wraps marks in the versioned document shape written next to the PDF. */
export function encodeAnnotations(
  marks: PdfAnnotation[],
  documentPath?: string,
): string {
  const document: AnnotationDocument = {
    version: ANNOTATION_SCHEMA_VERSION,
    documentPath,
    updatedAt: Date.now(),
    marks,
  }
  return JSON.stringify(document)
}

/**
 * Reads a rect in either shape: the native editor's `[x, y, w, h]` tuple or the
 * object form hand-built marks use.
 */
export function readRect(entry: PdfRect | PdfRectTuple): PdfRect {
  return Array.isArray(entry)
    ? { x: entry[0], y: entry[1], width: entry[2], height: entry[3] }
    : entry
}

/** Builds an SVG path from normalised points, expanded to page pixels. */
export function inkPath(strokes: PdfPoint[][], width: number, height: number, box?: PdfRect): string {
  const parts: string[] = []
  for (const stroke of strokes) {
    if (stroke.length === 0) continue
    parts.push(
      stroke
        .map((point, index) => {
          const x = box ? (box.x + point.x * box.width) * width : point.x * width
          const y = box ? (box.y + point.y * box.height) * height : point.y * height
          return `${index === 0 ? 'M' : 'L'}${x.toFixed(1)} ${y.toFixed(1)}`
        })
        .join(' '),
    )
  }
  return parts.join(' ')
}
