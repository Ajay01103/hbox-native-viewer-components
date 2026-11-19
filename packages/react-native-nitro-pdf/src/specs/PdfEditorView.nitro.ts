import type { HybridView, HybridViewProps } from 'react-native-nitro-modules'

// Nitro cannot represent anonymous object types in C++, so wrapped callbacks
// must be extracted into named interfaces.
export interface PdfVoidCallback {
  f: () => void
}

export interface PdfNumberCallback {
  f: (value: number) => void
}

export interface PdfStringCallback {
  f: (value: string) => void
}

/**
 * A complete, fully native PDF viewer and editor surface.
 *
 * The view owns its own chrome (app bar, tool strip, tool options, bottom
 * sheets) so the whole editor is painted by Android views instead of a tree of
 * React Native components. React only supplies the document and receives the
 * edits back as JSON.
 */
export interface PdfEditorViewProps extends HybridViewProps {
  /** Local path or `file://` URL. */
  documentPath: string
  /** `PdfAnnotation[]` serialised as JSON; applied when the document opens. */
  annotations: string
  /** 1-based page to show first. */
  initialPage: number
  /** Called when the user leaves the editor from the app bar. */
  onBack: PdfVoidCallback
  /** Called when the user shares the document from the app bar. */
  onShare: PdfVoidCallback
  /** Called when the user asks to hear the current page read aloud. */
  onReadAloud: PdfNumberCallback
  /** Emitted after every edit, with the full annotation list as JSON. */
  onAnnotationsChange: PdfStringCallback
  /** Emitted when the page in view changes. */
  onPageChange: PdfNumberCallback
  /** Emitted once the document is open, with its page count. */
  onDocumentLoaded: PdfNumberCallback
  /** Incremented by React on a hardware back press; the view closes its top layer. */
  backToken: number
}

export type PdfEditorView = HybridView<PdfEditorViewProps>
