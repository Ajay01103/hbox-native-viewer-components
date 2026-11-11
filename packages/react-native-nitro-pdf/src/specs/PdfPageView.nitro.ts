import type { HybridView, HybridViewProps } from 'react-native-nitro-modules'

// Nitro cannot represent anonymous object types in C++, so wrapped callbacks
// must be extracted into named interfaces.
export interface TextSelectionCallback {
  f: (text: string) => void
}

export interface PdfPageViewProps extends HybridViewProps {
  documentId: string
  page: number
  scale: number
  /** 0 | 90 | 180 | 270 */
  rotation: number
  /** Flat rects in page points. */
  highlights: number[]
  onTextSelected?: TextSelectionCallback
}

export type PdfPageView = HybridView<PdfPageViewProps>
