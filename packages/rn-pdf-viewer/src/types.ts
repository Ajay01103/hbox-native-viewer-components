import type { StyleProp, ViewStyle } from 'react-native'
import type { ReactNode } from 'react'
import type { SearchHit } from '@hbox/react-native-nitro-pdf'
import type { PdfAnnotation } from './editor'

export type { PdfAnnotation, PdfPoint, PdfRect, PdfTool } from './editor'

export type Zoom = number | 'fit-page' | 'fit-width' | 'automatic'

export type Rotation = 0 | 90 | 180 | 270

export interface PageOverlayProps {
  page: number
  /** Rendered page size in pixels. */
  width: number
  height: number
}

export interface PdfViewerApi {
  page: number
  pageCount: number
  zoom: Zoom
  rotation: Rotation
  hits: SearchHit[]
  goTo(page: number): void
  setZoom(zoom: Zoom): void
  rotate(dir: 1 | -1): void
  search(query: string): Promise<void>
  nextHit(): void
  prevHit(): void
  thumbnailUri(page: number, width: number): Promise<string>
}

export interface PDFViewerProps {
  /** Local path or `file://` URL. The app downloads remote files first. */
  src?: string
  defaultZoom?: Zoom
  showToolbar?: boolean
  /** `acrobat` (default) is the full document chrome; `classic` the plain bar. */
  chrome?: 'acrobat' | 'classic'
  /** Marks created by the editor, normalised to the page box. */
  initialAnnotations?: PdfAnnotation[]
  onAnnotationsChange?: (annotations: PdfAnnotation[]) => void
  onBack?: () => void
  /** Defaults to the platform share sheet with the document path. */
  onShare?: (src: string) => void
  /** Host-provided read-aloud; the viewer's speaker button calls this. */
  onReadAloud?: (page: number) => void
  showDownload?: boolean
  showRotateControls?: boolean
  renderToolbar?: (api: PdfViewerApi) => ReactNode
  renderThumbnails?: (api: PdfViewerApi) => ReactNode
  toolbarActions?: ReactNode
  renderPageOverlay?: (props: PageOverlayProps) => ReactNode
  pageStyle?: (page: number) => StyleProp<ViewStyle>
  onActivePageChange?: (page: number) => void
  onDocumentLoadSuccess?: (numPages: number) => void
  onDownload?: () => void
  onTextSelected?: (text: string) => void
}
