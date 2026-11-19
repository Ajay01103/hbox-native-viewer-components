import { useMemo, useRef, useState } from 'react'
import { PanResponder, StyleSheet, View } from 'react-native'
import Svg, { Circle, Path, Rect } from 'react-native-svg'
import { inkPath, readRect } from './editor'
import type { NewAnnotation, PdfAnnotation, PdfPoint, PdfTool } from './editor'

export interface PageEditorProps {
  page: number
  width: number
  height: number
  tool: PdfTool
  color: string
  strokeWidth: number
  /** Minimum band height (fraction of the page) for a highlight. */
  highlightHeight: number
  annotations: PdfAnnotation[]
  onAdd(annotation: NewAnnotation): void
  onRequestComment(page: number, point: PdfPoint): void
  onPlaceSignature(point: PdfPoint): void
}

interface Draft {
  start: PdfPoint
  current: PdfPoint
  points: PdfPoint[]
  moved: boolean
}

const SIGNATURE_WIDTH = 0.36
const SIGNATURE_HEIGHT = 0.18

function clamp01(value: number): number {
  if (!Number.isFinite(value)) return 0
  return Math.min(1, Math.max(0, value))
}

function rectFrom(start: PdfPoint, end: PdfPoint) {
  const x = Math.min(start.x, end.x)
  const y = Math.min(start.y, end.y)
  return {
    x,
    y,
    width: Math.abs(end.x - start.x),
    height: Math.abs(end.y - start.y),
  }
}

/**
 * Interactive mark layer for one page. Renders existing annotations and, while a
 * tool is selected, captures the gesture that creates the next one. Strokes are
 * captured in page-normalised coordinates so they survive zooming.
 */
export function PageEditor({
  page,
  width,
  height,
  tool,
  color,
  strokeWidth,
  highlightHeight,
  annotations,
  onAdd,
  onRequestComment,
  onPlaceSignature,
}: PageEditorProps) {
  const [draft, setDraft] = useState<Draft | null>(null)
  const draftRef = useRef<Draft | null>(null)
  // Point recorded on touch-down. Android can grant the responder a few move
  // events late, and without this the first part of every stroke was lost.
  const startRef = useRef<PdfPoint | null>(null)

  const pageAnnotations = useMemo(
    () => annotations.filter((annotation) => annotation.page === page),
    [annotations, page],
  )

  const responder = useMemo(() => {
    const normalise = (x: number, y: number): PdfPoint => ({
      x: width > 0 ? clamp01(x / width) : 0,
      y: height > 0 ? clamp01(y / height) : 0,
    })

    const update = (next: Draft | null) => {
      draftRef.current = next
      setDraft(next)
    }

    return PanResponder.create({
      onStartShouldSetPanResponder: (event) => {
        if (tool === 'none') return false
        if (!startRef.current) {
          startRef.current = normalise(event.nativeEvent.locationX, event.nativeEvent.locationY)
        }
        return true
      },
      onStartShouldSetPanResponderCapture: (event) => {
        if (tool === 'none') return false
        startRef.current = normalise(event.nativeEvent.locationX, event.nativeEvent.locationY)
        return false
      },
      onMoveShouldSetPanResponder: () => tool !== 'none',
      onPanResponderGrant: (event) => {
        const point =
          startRef.current ??
          normalise(event.nativeEvent.locationX, event.nativeEvent.locationY)
        update({ start: point, current: point, points: [point], moved: false })
      },
      onPanResponderMove: (event) => {
        const current = draftRef.current
        if (!current) return
        const point = normalise(event.nativeEvent.locationX, event.nativeEvent.locationY)
        const last = current.points[current.points.length - 1]
        const moved =
          current.moved ||
          Math.abs(point.x - current.start.x) > 0.004 ||
          Math.abs(point.y - current.start.y) > 0.004
        const points =
          tool === 'draw' && last && Math.hypot(point.x - last.x, point.y - last.y) < 0.002
            ? current.points
            : [...current.points, point]
        update({ start: current.start, current: point, points, moved })
      },
      onPanResponderTerminationRequest: () => false,
      onPanResponderRelease: () => {
        const finished = draftRef.current
        startRef.current = null
        update(null)
        if (!finished) return

        if (!finished.moved) {
          if (tool === 'comment') onRequestComment(page, finished.start)
          if (tool === 'sign') onPlaceSignature(finished.start)
          return
        }

        if (tool === 'highlight') {
          const rect = rectFrom(finished.start, finished.current)
          if (rect.width < 0.01) return
          // The size control sets a minimum band height so a quick swipe still
          // produces a readable marker stroke.
          onAdd({
            page,
            kind: 'highlight',
            color,
            rects: [{ ...rect, height: Math.max(rect.height, highlightHeight) }],
          })
        } else if (tool === 'draw') {
          if (finished.points.length < 2) return
          onAdd({
            page,
            kind: 'ink',
            strokes: [finished.points],
            strokeWidth,
            color,
          })
        }
      },
    })
  }, [
    color,
    height,
    highlightHeight,
    onAdd,
    onPlaceSignature,
    onRequestComment,
    page,
    strokeWidth,
    tool,
    width,
  ])

  return (
    <View style={StyleSheet.absoluteFill} pointerEvents="box-none">
      <View style={StyleSheet.absoluteFill} pointerEvents="none">
        <Svg width={width} height={height} style={StyleSheet.absoluteFill}>
        {pageAnnotations.map((annotation) => {
          if (annotation.kind === 'highlight') {
            return (annotation.rects ?? []).map((entry, index) => {
              const rect = readRect(entry)
              return (
                <Rect
                  key={`${annotation.id}-${index}`}
                  x={rect.x * width}
                  y={rect.y * height}
                  width={Math.max(1, rect.width * width)}
                  height={Math.max(1, rect.height * height)}
                  fill={annotation.color}
                  fillOpacity={0.38}
                  rx={2}
                />
              )
            })
          }
          if (annotation.kind === 'ink') {
            return (
              <Path
                key={annotation.id}
                d={inkPath(annotation.strokes ?? [], width, height)}
                stroke={annotation.color}
                strokeWidth={annotation.strokeWidth ?? 3}
                fill="none"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            )
          }
          if (annotation.kind === 'signature') {
            const box = annotation.rect
              ? readRect(annotation.rect)
              : { x: 0, y: 0, width: SIGNATURE_WIDTH, height: SIGNATURE_HEIGHT }
            return (
              <Path
                key={annotation.id}
                d={inkPath(annotation.strokes ?? [], width, height, box)}
                stroke={annotation.color}
                strokeWidth={annotation.strokeWidth ?? 2.4}
                fill="none"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            )
          }
          const point = annotation.point ?? { x: 0.5, y: 0.5 }
          return (
            <Circle
              key={annotation.id}
              cx={point.x * width}
              cy={point.y * height}
              r={9}
              fill="#f59e0b"
              stroke="#ffffff"
              strokeWidth={2}
            />
          )
        })}

        {draft && tool === 'highlight' ? (
          <Rect
            x={rectFrom(draft.start, draft.current).x * width}
            y={rectFrom(draft.start, draft.current).y * height}
            width={Math.max(1, rectFrom(draft.start, draft.current).width * width)}
            height={Math.max(1, rectFrom(draft.start, draft.current).height * height)}
            fill={color}
            fillOpacity={0.28}
            stroke={color}
            strokeWidth={1}
          />
        ) : null}

        {draft && tool === 'draw' ? (
          <Path
            d={inkPath([draft.points], width, height)}
            stroke={color}
            strokeWidth={strokeWidth}
            fill="none"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        ) : null}
      </Svg>
      </View>

      <View
        style={StyleSheet.absoluteFill}
        pointerEvents={tool === 'none' ? 'none' : 'auto'}
        {...responder.panHandlers}
      />
    </View>
  )
}
