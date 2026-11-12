import { useCallback, useEffect, useMemo, useState } from 'react'
import type { PdfDocument, SearchHit } from '@hbox/react-native-nitro-pdf'
import { usePdfDocument } from './usePdfDocument'
import { rotateBy } from './zoom'
import type { PdfViewerApi, Rotation, Zoom } from './types'

export interface UsePdfViewerOptions {
  src?: string
  password?: string
  defaultZoom?: Zoom
  onActivePageChange?: (page: number) => void
  onDocumentLoadSuccess?: (numPages: number) => void
}

export interface UsePdfViewerResult {
  api: PdfViewerApi
  document: PdfDocument | null
  pageCount: number
  loading: boolean
  error: Error | null
}

/**
 * Headless viewer state. Owns page / zoom / rotation / search so any skin
 * (default toolbar or the Tamagui package) can drive the same document.
 */
export function usePdfViewer({
  src,
  password,
  defaultZoom = 'fit-width',
  onActivePageChange,
  onDocumentLoadSuccess,
}: UsePdfViewerOptions): UsePdfViewerResult {
  const { document, pageCount, loading, error } = usePdfDocument(src, password)
  const [page, setPage] = useState(1)
  const [zoom, setZoom] = useState<Zoom>(defaultZoom)
  const [rotation, setRotation] = useState<Rotation>(0)
  const [hits, setHits] = useState<SearchHit[]>([])
  const [hitIndex, setHitIndex] = useState(0)

  useEffect(() => {
    setPage(1)
    setRotation(0)
    setHits([])
    setHitIndex(0)
  }, [src])

  useEffect(() => {
    if (pageCount > 0) {
      onDocumentLoadSuccess?.(pageCount)
    }
  }, [pageCount, onDocumentLoadSuccess])

  useEffect(() => {
    onActivePageChange?.(page)
  }, [page, onActivePageChange])

  const goTo = useCallback(
    (next: number) => {
      const lastPage = Math.max(pageCount, 1)
      setPage(Math.min(Math.max(1, Math.round(next)), lastPage))
    },
    [pageCount],
  )

  const rotate = useCallback((dir: 1 | -1) => {
    setRotation((current) => rotateBy(current, dir))
  }, [])

  const search = useCallback(
    async (query: string) => {
      const trimmed = query.trim()
      if (!document || trimmed.length === 0) {
        setHits([])
        setHitIndex(0)
        return
      }
      const found = await document.search(trimmed)
      // Native hits are 0-based; the viewer API is 1-based.
      const results = found.map((hit) => ({ ...hit, page: hit.page + 1 }))
      setHits(results)
      setHitIndex(0)
      const first = results[0]
      if (first) {
        setPage(first.page)
      }
    },
    [document],
  )

  const showHit = useCallback(
    (index: number) => {
      if (hits.length === 0) return
      const wrapped = ((index % hits.length) + hits.length) % hits.length
      setHitIndex(wrapped)
      const hit = hits[wrapped]
      if (hit) {
        setPage(hit.page)
      }
    },
    [hits],
  )

  const nextHit = useCallback(() => showHit(hitIndex + 1), [hitIndex, showHit])

  const prevHit = useCallback(() => showHit(hitIndex - 1), [hitIndex, showHit])

  const thumbnailUri = useCallback(
    (targetPage: number, width: number) => {
      if (!document) {
        return Promise.reject(new Error('No PDF document is open'))
      }
      return document.renderThumbnail(Math.max(0, targetPage - 1), width)
    },
    [document],
  )

  const api = useMemo<PdfViewerApi>(
    () => ({
      page,
      pageCount,
      zoom,
      rotation,
      hits,
      goTo,
      setZoom,
      rotate,
      search,
      nextHit,
      prevHit,
      thumbnailUri,
    }),
    [
      page,
      pageCount,
      zoom,
      rotation,
      hits,
      goTo,
      rotate,
      search,
      nextHit,
      prevHit,
      thumbnailUri,
    ],
  )

  return { api, document, pageCount, loading, error }
}
