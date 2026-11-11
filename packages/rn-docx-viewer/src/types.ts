import type { ReactNode } from 'react'
import type { StyleProp, ViewStyle } from 'react-native'
import type { DocInfo, DocxSource, TrackedChangesMode } from '@hbox/react-native-nitro-docx'

export type Zoom = number | 'fit-page' | 'fit-width' | 'automatic'

export type DocxErrorCode = 'LEGACY_DOC' | 'CORRUPT' | 'UNSUPPORTED' | 'TOO_LARGE' | 'UNKNOWN'

export interface DocxViewerError {
  code: DocxErrorCode
  message: string
}

export type ViewerSrc = string | DocxSource

export interface DocxViewerApi {
  page: number
  pageCount: number
  zoom: Zoom
  isDark: boolean
  info: DocInfo | null
  setZoom(zoom: Zoom): void
  goToPage(page: number): void
  goToAnchor(id: string): void
  search(query: string): Promise<{ page: number; anchorId: string; snippet: string }[]>
  getText(): Promise<string>
}

export interface DocxViewerProps {
  src: ViewerSrc
  fileName?: string
  /** Default `fit-width`. */
  defaultZoom?: Zoom
  theme?: 'light' | 'dark' | 'system'
  /** Controlled dark mode, matching the Extend viewer. */
  isDark?: boolean
  onIsDarkChange?: (isDark: boolean) => void
  showToolbar?: boolean
  showUpload?: boolean
  showDownload?: boolean
  showThumbnails?: boolean
  showOutline?: boolean
  showComments?: boolean
  toolbarActions?: ReactNode
  trackedChanges?: TrackedChangesMode
  onTrackedChangesChange?: (mode: TrackedChangesMode) => void
  onRequestUpload?: () => void
  onRequestDownload?: (src: DocxSource) => void
  onLoad?: (info: DocInfo) => void
  onPageChange?: (page: number, total: number) => void
  onError?: (error: DocxViewerError) => void
  style?: StyleProp<ViewStyle>
}
