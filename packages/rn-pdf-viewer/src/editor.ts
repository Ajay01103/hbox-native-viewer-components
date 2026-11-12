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

/**
 * One mark on one page. Geometry is normalised to the rendered page box
 * (0..1), so marks stay attached to the same text at any zoom level, rotation
 * or screen size.
 */
export interface PdfAnnotation {
  id: string
  page: number
  kind: 'highlight' | 'ink' | 'note' | 'signature'
  color: string
  rects?: PdfRect[]
  strokes?: PdfPoint[][]
  strokeWidth?: number
  point?: PdfPoint
  text?: string
  /** Placement box for a signature stamp. */
  rect?: PdfRect
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
