/**
 * Typed model for the annotation JSON that `PdfEditorView` reads and emits.
 *
 * The native side hands the list over as a string, so `decodeAnnotations` is
 * the only place that knows the wire shape: consumers get `rects` (one box per
 * selected line) and `glyph` as real fields instead of guessing at the JSON.
 *
 * Wire versions
 * -------------
 * v0 — no `version` key (bare array, or a document written before multi-line
 *      highlights): a mark had only its bounding `rect`, `rects` was absent and
 *      a markup mark had no `glyph` style.
 * v1 — current: `rects` holds one normalised box per selected line and a
 *      markup mark keeps `glyph` as its style.
 *
 * Older payloads are migrated on read, mirroring `PdfMarkCodec.migrate` on the
 * native side, so a stored sidecar and a JS consumer agree.
 */

export const PDF_ANNOTATION_VERSION = 1

export type PdfAnnotationKind = 'highlight' | 'ink' | 'note' | 'signature' | 'stamp'

/** Style carried in `glyph` by markup annotations. */
export type PdfMarkupStyle = 'highlight' | 'underline' | 'strikeout'

/** `[left, top, width, height]`, normalised to the page (0..1). */
export type NormalisedBox = readonly [number, number, number, number]

export interface PdfAnnotation {
  id: string
  kind: PdfAnnotationKind
  /** 1-based page number. */
  page: number
  /** Bounding box of every mark, ink included. */
  rect: NormalisedBox
  /** One box per selected line for markup marks; empty for ink and stamps. */
  rects: NormalisedBox[]
  /** `#AARRGGBB`. */
  color: string
  /** Stroke width relative to the page width. */
  width: number
  /** Flat `[x, y, ...]` points, normalised, for ink and signatures. */
  points: number[]
  text: string
  /** Stamp glyph (`check`, `cross`, `dot`, `box`, `line`) or markup style. */
  glyph: string
}

export interface PdfAnnotationDocument {
  version: number
  documentPath: string
  updatedAt: number
  marks: PdfAnnotation[]
}

const EMPTY_BOX: NormalisedBox = [0, 0, 0, 0]

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function toNumber(value: unknown, fallback = 0): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

function toBox(value: unknown): NormalisedBox {
  if (!Array.isArray(value) || value.length < 4) return EMPTY_BOX
  return [toNumber(value[0]), toNumber(value[1]), toNumber(value[2]), toNumber(value[3])]
}

function toBoxList(value: unknown): NormalisedBox[] {
  if (!Array.isArray(value)) return []
  return value.filter(Array.isArray).map(toBox)
}

function isZeroBox(box: NormalisedBox): boolean {
  return box[2] <= 0 || box[3] <= 0
}

const KINDS: readonly PdfAnnotationKind[] = ['highlight', 'ink', 'note', 'signature', 'stamp']

function toKind(value: unknown): PdfAnnotationKind {
  return KINDS.includes(value as PdfAnnotationKind) ? (value as PdfAnnotationKind) : 'highlight'
}

/** Parses one mark, applying the v0 → v1 migration. */
export function decodeAnnotation(value: unknown, version = 0): PdfAnnotation | null {
  if (!isRecord(value)) return null
  const annotation: PdfAnnotation = {
    id: typeof value.id === 'string' ? value.id : '',
    kind: toKind(value.kind),
    page: Math.max(1, Math.round(toNumber(value.page, 1))),
    rect: toBox(value.rect),
    rects: toBoxList(value.rects),
    color: typeof value.color === 'string' ? value.color : '#FFFFFF00',
    width: toNumber(value.width, 0.004),
    points: Array.isArray(value.points) ? value.points.map((entry) => toNumber(entry)) : [],
    text: typeof value.text === 'string' ? value.text : '',
    glyph: typeof value.glyph === 'string' ? value.glyph : '',
  }
  if (version >= PDF_ANNOTATION_VERSION) return annotation
  // v0: the single bounding rect was the whole geometry, and markup had no style.
  if (annotation.rects.length === 0 && !isZeroBox(annotation.rect)) {
    annotation.rects = [annotation.rect]
  }
  if (annotation.kind === 'highlight' && annotation.glyph === '') {
    annotation.glyph = 'highlight'
  }
  return annotation
}

/**
 * Reads the `annotations` prop or the emitted JSON. Accepts the versioned
 * document, a bare array of marks (v0), and malformed input — which yields an
 * empty document rather than throwing at a call site.
 */
export function decodeAnnotations(raw: string | null | undefined): PdfAnnotationDocument {
  const empty: PdfAnnotationDocument = { version: PDF_ANNOTATION_VERSION, documentPath: '', updatedAt: 0, marks: [] }
  if (!raw) return empty
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return empty
  }
  if (Array.isArray(parsed)) {
    return { ...empty, marks: parsed.map((entry) => decodeAnnotation(entry, 0)).filter(isAnnotation) }
  }
  if (!isRecord(parsed)) return empty
  const version = Math.round(toNumber(parsed.version, 0))
  const marks = Array.isArray(parsed.marks) ? parsed.marks : []
  return {
    version,
    documentPath: typeof parsed.documentPath === 'string' ? parsed.documentPath : '',
    updatedAt: toNumber(parsed.updatedAt),
    marks: marks.map((entry) => decodeAnnotation(entry, version)).filter(isAnnotation),
  }
}

function isAnnotation(value: PdfAnnotation | null): value is PdfAnnotation {
  return value !== null
}

/**
 * Boxes to paint for a mark: the per-line `rects` when present, otherwise the
 * single bounding `rect` (ink, stamps, and anything still on the v0 shape).
 */
export function annotationBoxes(annotation: PdfAnnotation): NormalisedBox[] {
  return annotation.rects.length > 0 ? annotation.rects : [annotation.rect]
}

/** Style of a markup annotation, defaulting to a plain highlight. */
export function annotationStyle(annotation: PdfAnnotation): PdfMarkupStyle {
  const glyph = annotation.glyph
  return glyph === 'underline' || glyph === 'strikeout' ? glyph : 'highlight'
}
