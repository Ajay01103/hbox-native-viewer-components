export interface PageSize {
  width: number
  height: number
}

/**
 * A DOCX can be passed as a `file://` URI or as base64 bytes. `uri` wins when
 * both are present. Remote URLs are not fetched natively: download first.
 */
export interface DocxSource {
  uri?: string
  base64?: string
}

export interface OutlineItem {
  id: string
  title: string
  /** 1 = Heading 1. */
  level: number
  page: number
}

export interface DocComment {
  id: string
  author: string
  date?: string
  text: string
  anchorId: string
  page: number
  parentId?: string
}

export interface DocInfo {
  fileName: string
  title: string
  /** `false` until background pagination of a very large document settles. */
  pageCountFinal: boolean
  pageCount: number
  pageSize: PageSize
  outline: OutlineItem[]
  comments: DocComment[]
  hasTrackedChanges: boolean
  /** Human-readable degradations, e.g. "text box rendered as text". */
  warnings: string[]
}

export interface SearchHit {
  page: number
  anchorId: string
  snippet: string
}

/** Concrete colors resolved from the host app's Tamagui theme. */
export interface DocxPalette {
  appBg: string
  pageBg: string
  pageBorder: string
  text: string
  muted: string
  selection: string
  commentHighlight: string
  insertColor: string
  deleteColor: string
  /** When true native remaps text/background colors; images stay untouched. */
  pageDarkMode: boolean
}

export type TrackedChangesMode = 'markup' | 'final' | 'original'
