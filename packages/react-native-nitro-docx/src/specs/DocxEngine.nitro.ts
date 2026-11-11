import type { HybridObject } from 'react-native-nitro-modules'
import type { DocComment, DocInfo, DocxSource, OutlineItem, PageSize, SearchHit } from './types'

/**
 * A parsed DOCX document. One engine is shared by the page view, the thumbnail
 * views and search, so the document is parsed and paginated exactly once.
 *
 * Parsing and pagination run off the JS thread.
 */
export interface DocxEngine extends HybridObject<{ ios: 'swift'; android: 'kotlin' }> {
  readonly id: string
  readonly pageCount: number
  /** Stable: throws `LEGACY_DOC` for `.doc`, `CORRUPT` for unreadable zips. */
  load(source: DocxSource): Promise<DocInfo>
  getPageSize(): PageSize
  search(query: string): Promise<SearchHit[]>
  getText(fromPage?: number, toPage?: number): Promise<string>
  getPageText(page: number): Promise<string>
  getOutline(): Promise<OutlineItem[]>
  getComments(): Promise<DocComment[]>
}
