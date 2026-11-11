import type { HybridView, HybridViewProps } from 'react-native-nitro-modules'
import type { DocxPalette } from './types'

export interface DocxThumbnailProps extends HybridViewProps {
  documentId: string
  page: number
  palette: DocxPalette
  /** Rendered at this scale; JS sizes the view from the page aspect ratio. */
  scale: number
}

export type DocxThumbnail = HybridView<DocxThumbnailProps>
