import { useEffect, useState } from 'react'
import { createPdfDocument } from '@hbox/react-native-nitro-pdf'
import type { PdfDocument } from '@hbox/react-native-nitro-pdf'
import { toLocalPath } from './path'

export interface PdfDocumentState {
  document: PdfDocument | null
  pageCount: number
  loading: boolean
  error: Error | null
}

const EMPTY: PdfDocumentState = {
  document: null,
  pageCount: 0,
  loading: false,
  error: null,
}

/** Opens the document for `src` and closes it when `src` changes or unmounts. */
export function usePdfDocument(
  src?: string,
  password?: string,
): PdfDocumentState {
  const [state, setState] = useState<PdfDocumentState>(EMPTY)

  useEffect(() => {
    if (!src) {
      setState(EMPTY)
      return
    }

    let cancelled = false
    const document = createPdfDocument()
    setState({ document, pageCount: 0, loading: true, error: null })

    document
      .open(toLocalPath(src), password)
      .then(() => {
        if (cancelled) return
        setState({
          document,
          pageCount: document.pageCount,
          loading: false,
          error: null,
        })
      })
      .catch((cause: unknown) => {
        if (cancelled) return
        setState({
          document: null,
          pageCount: 0,
          loading: false,
          error: cause instanceof Error ? cause : new Error(String(cause)),
        })
      })

    return () => {
      cancelled = true
      try {
        document.close()
      } catch {
        // Native close waits for in-flight renders; if it still fails, the
        // screen is going away anyway and must not throw during unmount.
      }
    }
  }, [src, password])

  return state
}
