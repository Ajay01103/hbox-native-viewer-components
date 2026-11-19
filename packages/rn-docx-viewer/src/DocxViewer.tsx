import { forwardRef, useCallback, useEffect, useImperativeHandle, useMemo, useState } from 'react'
import type { ForwardedRef } from 'react'
import { Platform, StyleSheet, View } from 'react-native'
import { DocxEditorView } from '@hbox/react-native-nitro-docx'
import type { TrackedChangesMode } from '@hbox/react-native-nitro-docx'
import { DocxViewerFallback } from './DocxViewerFallback'
import { ErrorState, LoadingState } from './States'
import { resolvePalette } from './palette'
import { resolveScale, zoomLabel } from './zoom'
import { useDocx } from './useDocx'
import type { DocxViewerApi, DocxViewerProps, Zoom } from './types'

const TRACKED_NEXT: Record<TrackedChangesMode, TrackedChangesMode> = {
  markup: 'final',
  final: 'original',
  original: 'markup',
}

/**
 * DOCX viewer and reader.
 *
 * On Android the whole viewer is native: the toolbar, the Pages/Outline/
 * Comments tab strip, the panels and the page canvas are Android views
 * (`DocxEditorView`), so the chrome shares the native painter's palette and
 * never fights the page surface for gestures. React keeps the engine, the zoom
 * mode and the theme, and receives every control press as a callback.
 *
 * iOS keeps the React chrome until the native viewer is ported there.
 */
export const DocxViewer = forwardRef<DocxViewerApi, DocxViewerProps>(function DocxViewer(props, ref) {
  // Custom toolbar nodes and a hidden toolbar cannot be painted natively yet, so
  // those hosts keep the React chrome instead of silently losing features.
  if (Platform.OS !== 'android' || props.toolbarActions || props.showToolbar === false) {
    return <DocxViewerFallback {...props} ref={ref} />
  }
  return <NativeDocxViewer {...props} forwardedRef={ref} />
})

function NativeDocxViewer({
  src,
  fileName,
  defaultZoom = 'fit-width',
  theme = 'light',
  isDark: controlledDark,
  onIsDarkChange,
  showUpload = true,
  showDownload = true,
  showThumbnails = true,
  showOutline = true,
  showComments = true,
  style,
  trackedChanges = 'markup',
  onTrackedChangesChange,
  onRequestUpload,
  onRequestDownload,
  onLoad,
  onPageChange,
  onError,
  forwardedRef,
}: DocxViewerProps & { forwardedRef: ForwardedRef<DocxViewerApi> }) {
  const [internalDark, setInternalDark] = useState(theme === 'dark')
  const isDark = controlledDark ?? internalDark

  const [zoom, setZoom] = useState<Zoom>(defaultZoom)
  // Native pinch zoom multiplies the resolved scale; it is reported back only
  // so the toolbar can show the real level.
  const [pinchScale, setPinchScale] = useState(1)
  const [page, setPage] = useState(1)
  const [nonce, setNonce] = useState(0)
  // The native chrome reports the page canvas size in dp, which is what the fit
  // modes are resolved against.
  const [viewport, setViewport] = useState({ width: 0, height: 0 })

  const { engine, info, loading, error } = useDocx({ src, fileName, onLoad, onError })

  const palette = useMemo(() => resolvePalette(isDark), [isDark])
  const pageCount = info?.pageCount ?? 0
  const pageSize = info?.pageSize ?? { width: 595, height: 842 }

  const scale = useMemo(
    () => (info ? resolveScale(zoom, pageSize, viewport) : 1),
    [info, pageSize, viewport, zoom],
  )

  useEffect(() => {
    // A new base scale (zoom button, rotation, resize) supersedes the pinch.
    setPinchScale(1)
  }, [scale])

  const setDark = useCallback(
    (next: boolean) => {
      if (controlledDark === undefined) setInternalDark(next)
      onIsDarkChange?.(next)
    },
    [controlledDark, onIsDarkChange],
  )

  const goToPage = useCallback(
    (next: number) => {
      const clamped = Math.max(1, Math.min(next, Math.max(1, pageCount)))
      setPage(clamped)
      setNonce((value) => value + 1)
    },
    [pageCount],
  )

  const goToAnchor = useCallback(
    (anchorId: string) => {
      const fromOutline = info?.outline.find((item) => item.id === anchorId)
      const fromComment = info?.comments.find((comment) => comment.anchorId === anchorId)
      const target = fromOutline?.page ?? fromComment?.page
      if (target && target > 0) goToPage(target)
    },
    [goToPage, info],
  )

  const api = useMemo<DocxViewerApi>(
    () => ({
      page,
      pageCount,
      zoom,
      isDark,
      info,
      setZoom,
      goToPage,
      goToAnchor,
      search: (query: string) => engine.search(query),
      getText: () => engine.getText(),
    }),
    [engine, goToPage, goToAnchor, info, isDark, page, pageCount, zoom],
  )

  useImperativeHandle(forwardedRef, () => api, [api])

  const downloadSource = useMemo(
    () => (typeof src === 'string' ? { uri: src } : src),
    [src],
  )

  const outlineJson = useMemo(() => JSON.stringify(info?.outline ?? []), [info])
  const commentsJson = useMemo(() => JSON.stringify(info?.comments ?? []), [info])

  const handlers = useMemo(
    () => ({
      onPageChange: {
        f: (nextPage: number, total: number) => {
          const clamped = Math.max(1, Math.min(nextPage, Math.max(1, total)))
          setPage(clamped)
          onPageChange?.(clamped, total)
        },
      },
      onZoomSettled: {
        f: (value: number) => setPinchScale(value > 0 ? value : 1),
      },
      onViewport: {
        f: (width: number, height: number) => {
          setViewport((current) =>
            current.width === width && current.height === height ? current : { width, height },
          )
        },
      },
      onZoomOut: {
        f: () =>
          setZoom((current) =>
            typeof current === 'number' ? Math.max(0.25, current - 0.1) : scale,
          ),
      },
      onZoomIn: {
        f: () =>
          setZoom((current) =>
            typeof current === 'number' ? Math.min(4, current + 0.1) : scale + 0.1,
          ),
      },
      onZoomReset: { f: () => setZoom('fit-width') },
      onPrevious: { f: () => goToPage(page - 1) },
      onNext: { f: () => goToPage(page + 1) },
      onToggleTheme: { f: () => setDark(!isDark) },
      onUpload: { f: () => onRequestUpload?.() },
      onDownload: { f: () => onRequestDownload?.(downloadSource) },
      onCycleChanges: { f: () => onTrackedChangesChange?.(TRACKED_NEXT[trackedChanges]) },
    }),
    [
      goToPage,
      isDark,
      onPageChange,
      onRequestDownload,
      onRequestUpload,
      onTrackedChangesChange,
      downloadSource,
      page,
      scale,
      setDark,
      trackedChanges,
    ],
  )

  if (error) {
    return (
      <View style={[styles.root, { backgroundColor: palette.appBg }, style]}>
        <ErrorState error={error} palette={palette} />
      </View>
    )
  }

  if (loading || !info) {
    return (
      <View style={[styles.root, { backgroundColor: palette.appBg }, style]}>
        <LoadingState palette={palette} />
      </View>
    )
  }

  const pinched = pinchScale > 1.02 || pinchScale < 0.98
  const label = pinched
    ? `${Math.round(scale * pinchScale * 100)}%`
    : zoomLabel(zoom, scale)

  return (
    <DocxEditorView
      style={[styles.root, { backgroundColor: palette.appBg }, style]}
      documentId={engine.id}
      scale={scale}
      scrollToPage={page}
      scrollNonce={nonce}
      palette={palette}
      trackedChanges={trackedChanges}
      fileName={info.fileName || fileName || 'Document'}
      page={page}
      pageCount={pageCount}
      zoomLabel={label}
      isDark={isDark}
      showUpload={showUpload && Boolean(onRequestUpload)}
      showDownload={showDownload && Boolean(onRequestDownload)}
      showThumbnails={showThumbnails}
      showOutline={showOutline}
      showComments={showComments}
      showChanges={Boolean(onTrackedChangesChange)}
      outlineJson={outlineJson}
      commentsJson={commentsJson}
      onPageChange={handlers.onPageChange}
      onZoomSettled={handlers.onZoomSettled}
      onViewport={handlers.onViewport}
      onZoomOut={handlers.onZoomOut}
      onZoomIn={handlers.onZoomIn}
      onZoomReset={handlers.onZoomReset}
      onPrevious={handlers.onPrevious}
      onNext={handlers.onNext}
      onToggleTheme={handlers.onToggleTheme}
      onUpload={handlers.onUpload}
      onDownload={handlers.onDownload}
      onCycleChanges={handlers.onCycleChanges}
    />
  )
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
  },
})
