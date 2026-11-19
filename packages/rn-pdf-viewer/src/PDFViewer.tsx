import { useCallback, useEffect, useRef, useState } from 'react'
import { BackHandler, Platform, StyleSheet } from 'react-native'
import { PdfEditorView } from './nativeEditor'
import { PdfViewerFallback } from './PDFViewerFallback'
import type { PDFViewerProps } from './types'
import { decodeAnnotations } from './editor'

/**
 * Single-package PDF viewer and editor.
 *
 * On Android the whole surface is native (`PdfEditorView`): the app bar, tool
 * strip, contextual tool options, comment threads, the signature pad and every
 * gesture are painted by Kotlin views, so there is no React chrome to fight the
 * native page renderer and no "arm the tool, then act" second tap. React only
 * hands over the document and receives edits back through
 * `onAnnotationsChange`.
 *
 * iOS keeps the previous React implementation until the native surface is
 * ported there.
 */
export function PDFViewer(props: PDFViewerProps) {
  if (Platform.OS !== 'android' || !props.src) {
    return <PdfViewerFallback {...props} />
  }
  return <NativePdfViewer {...props} />
}

function NativePdfViewer({
  src,
  initialAnnotations,
  onAnnotationsChange,
  onBack,
  onShare,
  onReadAloud,
  onActivePageChange,
  onDocumentLoadSuccess,
}: PDFViewerProps) {
  // The native editor owns the edit list once the document is open; the initial
  // list is handed over exactly once so re-renders never overwrite live edits.
  const [annotations] = useState(() => JSON.stringify(initialAnnotations ?? []))
  const [backToken, setBackToken] = useState(0)
  const mounted = useRef(true)

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

  // The native view closes its own sheet or tool first; it calls `onBack` only
  // when there is nothing left to dismiss.
  useEffect(() => {
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      setBackToken((token) => token + 1)
      return true
    })
    return () => subscription.remove()
  }, [])

  const handleAnnotations = useCallback(
    (json: string) => {
      if (!mounted.current || !onAnnotationsChange) return
      // Accepts the bare array the editor emits and the versioned document,
      // migrating older marks as they arrive.
      onAnnotationsChange(decodeAnnotations(json))
    },
    [onAnnotationsChange],
  )

  return (
    <PdfEditorView
      style={styles.root}
      documentPath={src ?? ''}
      annotations={annotations}
      initialPage={1}
      onBack={{ f: () => onBack?.() }}
      onShare={{ f: () => onShare?.(src ?? '') }}
      onReadAloud={{ f: (page: number) => onReadAloud?.(page) }}
      onAnnotationsChange={{ f: handleAnnotations }}
      onPageChange={{ f: (page: number) => onActivePageChange?.(page) }}
      onDocumentLoaded={{ f: (total: number) => onDocumentLoadSuccess?.(total) }}
      backToken={backToken}
    />
  )
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#f1f2f5',
  },
})
