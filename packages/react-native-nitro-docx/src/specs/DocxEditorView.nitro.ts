import type { HybridView, HybridViewProps } from 'react-native-nitro-modules'
import type { DocxPalette } from './types'

// Nitro cannot represent anonymous function types, so every callback is
// declared as a named interface with a single `f` member.
export interface ViewerVoidCallback {
  f: () => void
}

export interface ViewerPageCallback {
  f: (page: number, total: number) => void
}

export interface ViewerZoomCallback {
  f: (scale: number) => void
}

export interface ViewerSizeCallback {
  f: (width: number, height: number) => void
}

/**
 * The complete DOCX viewer chrome, painted natively.
 *
 * The document surface (`DocxPageListView`) already lives in Kotlin; this host
 * adds the toolbar, the tab strip and the page/outline/comment panels so the
 * whole viewer — not only the page canvas — is native views.
 *
 * React keeps owning the engine, the zoom mode and the theme, and passes the
 * values the chrome displays; every button reports back through a callback.
 */
export interface DocxEditorViewProps extends HybridViewProps {
  /** Id of the open `DocxEngine`. */
  documentId: string
  /** Base zoom, in dp per document point. */
  scale: number
  /** Page to jump to; re-applied whenever `scrollNonce` changes. */
  scrollToPage: number
  scrollNonce: number
  palette: DocxPalette
  /** `markup` | `final` | `original` */
  trackedChanges: string
  fileName: string
  page: number
  pageCount: number
  /** Display text for the zoom control, e.g. `Fit width` or `84%`. */
  zoomLabel: string
  isDark: boolean
  showUpload: boolean
  showDownload: boolean
  showThumbnails: boolean
  showOutline: boolean
  showComments: boolean
  /** Whether the host handles tracked-changes switching. */
  showChanges: boolean
  /** `OutlineItem[]` as JSON. */
  outlineJson: string
  /** `DocComment[]` as JSON. */
  commentsJson: string
  onPageChange: ViewerPageCallback
  onZoomSettled: ViewerZoomCallback
  /** Page canvas size in dp, so React can resolve fit modes. */
  onViewport: ViewerSizeCallback
  onZoomOut: ViewerVoidCallback
  onZoomIn: ViewerVoidCallback
  onZoomReset: ViewerVoidCallback
  onPrevious: ViewerVoidCallback
  onNext: ViewerVoidCallback
  onToggleTheme: ViewerVoidCallback
  onUpload: ViewerVoidCallback
  onDownload: ViewerVoidCallback
  onCycleChanges: ViewerVoidCallback
}

export type DocxEditorView = HybridView<DocxEditorViewProps>
