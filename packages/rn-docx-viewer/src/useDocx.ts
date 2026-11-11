import { useEffect, useMemo, useRef, useState } from 'react'
import { createDocxEngine } from '@hbox/react-native-nitro-docx'
import type { DocInfo, DocxEngine, DocxSource } from '@hbox/react-native-nitro-docx'
import type { DocxErrorCode, DocxViewerError, ViewerSrc } from './types'

export function toDocxSource(src: ViewerSrc): DocxSource {
  return typeof src === 'string' ? { uri: src } : src
}

function classify(message: string): DocxErrorCode {
  const upper = message.toUpperCase()
  if (upper.includes('LEGACY_DOC') || upper.includes('.DOC IS')) return 'LEGACY_DOC'
  if (upper.includes('CORRUPT') || upper.includes('NOT A ZIP') || upper.includes('DOCX')) return 'CORRUPT'
  if (upper.includes('TOO_LARGE') || upper.includes('TOO LARGE')) return 'TOO_LARGE'
  if (upper.includes('UNSUPPORTED')) return 'UNSUPPORTED'
  return 'UNKNOWN'
}

export interface UseDocxOptions {
  src: ViewerSrc
  fileName?: string
  onLoad?: (info: DocInfo) => void
  onError?: (error: DocxViewerError) => void
}

export interface UseDocxResult {
  engine: DocxEngine
  info: DocInfo | null
  loading: boolean
  error: DocxViewerError | null
}

/**
 * Owns the native engine for one `src`. Parsing and pagination happen natively
 * off the JS thread; JS only receives the small `DocInfo` summary.
 */
export function useDocx({ src, fileName, onLoad, onError }: UseDocxOptions): UseDocxResult {
  const engine = useMemo(() => createDocxEngine(), [])
  const [info, setInfo] = useState<DocInfo | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<DocxViewerError | null>(null)

  const callbacks = useRef({ onLoad, onError })
  callbacks.current = { onLoad, onError }

  const source = toDocxSource(src)
  const sourceKey = source.uri ?? source.base64?.slice(0, 64) ?? ''

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    setInfo(null)

    engine
      .load(source)
      .then((loaded) => {
        if (cancelled) return
        const next = fileName ? { ...loaded, fileName } : loaded
        setInfo(next)
        callbacks.current.onLoad?.(next)
      })
      .catch((cause: unknown) => {
        if (cancelled) return
        const message = cause instanceof Error ? cause.message : String(cause)
        const failure: DocxViewerError = { code: classify(message), message }
        setError(failure)
        callbacks.current.onError?.(failure)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
    // `engine` is stable for the lifetime of this hook instance.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sourceKey])

  useEffect(
    () => () => {
      engine.dispose()
    },
    [engine],
  )

  return { engine, info, loading, error }
}
