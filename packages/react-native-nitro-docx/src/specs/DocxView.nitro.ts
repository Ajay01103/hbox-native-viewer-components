import type { HybridView, HybridViewProps } from 'react-native-nitro-modules'
import type { DocxPalette } from './types'

// Nitro cannot represent anonymous function types, so every callback is
// declared as a named interface with a single `f` member.
export interface PageChangeCallback {
  f: (page: number, total: number) => void
}

export interface LayoutProgressCallback {
  f: (laidOutPages: number, final: boolean) => void
}

export interface ChromeVisibilityCallback {
  f: (visible: boolean) => void
}

export interface CommentPressCallback {
  f: (id: string) => void
}

export interface SelectionChangeCallback {
  f: (hasSelection: boolean) => void
}

export interface ZoomSettledCallback {
  f: (scale: number) => void
}

export interface DocxViewProps extends HybridViewProps {
  /** Id of the `DocxEngine` that owns the parsed document. */
  documentId: string
  /** Concrete scale factor; fit modes are resolved in JS from `pageSize`. */
  scale: number
  /** Page to jump to. Re-applied whenever `scrollNonce` changes. */
  scrollToPage: number
  scrollNonce: number
  palette: DocxPalette
  /** `markup` | `final` | `original` */
  trackedChanges: string
  onPageChange?: PageChangeCallback
  onLayoutProgress?: LayoutProgressCallback
  onChromeVisibility?: ChromeVisibilityCallback
  onCommentPress?: CommentPressCallback
  onSelectionChange?: SelectionChangeCallback
  onZoomSettled?: ZoomSettledCallback
}

export type DocxView = HybridView<DocxViewProps>
