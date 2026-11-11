import type { HybridObject } from 'react-native-nitro-modules'

export interface PageSize {
  width: number
  height: number
}

/** Rects are flat `[x, y, w, h, ...]` in page points, top-left origin. */
export interface SearchHit {
  page: number
  rects: number[]
}

export interface PdfDocument
  extends HybridObject<{ ios: 'swift'; android: 'kotlin' }> {
  readonly id: string
  readonly pageCount: number
  /** Local path or `file://` URL only; the app downloads remote files first. */
  open(path: string, password?: string): Promise<void>
  getPageSize(page: number): PageSize
  /** Returns a cached thumbnail file path. */
  renderThumbnail(page: number, width: number): Promise<string>
  search(query: string): Promise<SearchHit[]>
  getPageText(page: number): Promise<string>
  close(): void
}
