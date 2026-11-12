import { forwardRef, useCallback, useEffect, useImperativeHandle, useMemo, useRef, useState } from 'react'
import { StyleSheet, View } from 'react-native'
import type { LayoutChangeEvent } from 'react-native'
import { DocxView } from '@hbox/react-native-nitro-docx'
import type { DocComment, OutlineItem, TrackedChangesMode } from '@hbox/react-native-nitro-docx'
import { DocxToolbar } from './DocxToolbar'
import { CommentsPanel, OutlinePanel, ThumbnailPanel } from './Panels'
import { ErrorState, LoadingState } from './States'
import { resolvePalette } from './palette'
import type { DocxViewerApi, DocxViewerProps, Zoom } from './types'
import { useDocx } from './useDocx'
import { resolveScale } from './zoom'

type OpenPanel = 'none' | 'pages' | 'outline' | 'comments'

export const DocxViewer = forwardRef<DocxViewerApi, DocxViewerProps>(function DocxViewer(
  {
    src,
    fileName,
    defaultZoom = 'fit-width',
    theme = 'light',
    isDark: controlledDark,
    onIsDarkChange,
    showToolbar = true,
    showUpload = true,
    showDownload = true,
    showThumbnails = true,
    showOutline = true,
    showComments = true,
    toolbarActions,
    trackedChanges = 'markup',
    onTrackedChangesChange,
    onRequestUpload,
    onRequestDownload,
    onLoad,
    onPageChange,
    onError,
    style,
  },
  ref,
) {
  const [internalDark, setInternalDark] = useState(theme === 'dark')
  const isDark = controlledDark ?? internalDark

  const [zoom, setZoom] = useState<Zoom>(defaultZoom)
  // Native pinch zoom is a multiplier on top of the resolved scale. It is
  // reported back here only so the toolbar can show the real zoom level.
  const [pinchScale, setPinchScale] = useState(1)
  const [page, setPage] = useState(1)
  const [nonce, setNonce] = useState(0)
  const [viewport, setViewport] = useState({ width: 0, height: 0 })
  const [panel, setPanel] = useState<OpenPanel>('none')
  const [hasSelection, setHasSelection] = useState(false)
  const [layoutFinal, setLayoutFinal] = useState(true)
  const totalRef = useRef(1)

  const { engine, info, loading, error } = useDocx({ src, fileName, onLoad, onError })

  const palette = useMemo(() => resolvePalette(isDark), [isDark])
  const pageCount = info?.pageCount ?? 0
  const pageSize = info?.pageSize ?? { width: 595, height: 842 }
  totalRef.current = pageCount

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
      setPanel('none')
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

  useImperativeHandle(ref, () => api, [api])

  useEffect(() => {
    if (!hasSelection) return
    const timer = setTimeout(() => setHasSelection(false), 1800)
    return () => clearTimeout(timer)
  }, [hasSelection])

  const onLayout = useCallback((event: LayoutChangeEvent) => {
    const { width, height } = event.nativeEvent.layout
    setViewport({ width, height })
  }, [])

  const onSelectOutline = useCallback(
    (item: OutlineItem) => {
      goToPage(item.page)
    },
    [goToPage],
  )

  const onSelectComment = useCallback(
    (comment: DocComment) => {
      goToPage(comment.page)
    },
    [goToPage],
  )

  const onNativePageChange = useMemo(
    () => ({
      f: (nextPage: number, total: number) => {
        const clamped = Math.max(1, Math.min(nextPage, Math.max(1, total)))
        setPage(clamped)
        onPageChange?.(clamped, total)
      },
    }),
    [onPageChange],
  )

  const onNativeLayoutProgress = useMemo(
    () => ({
      f: (_laidOut: number, final: boolean) => setLayoutFinal(final),
    }),
    [],
  )

  const onNativeCommentPress = useMemo(
    () => ({
      f: (id: string) => {
        const match = info?.comments.find((comment) => comment.id === id)
        if (match) setPanel('comments')
      },
    }),
    [info],
  )

  const onNativeSelection = useMemo(
    () => ({
      f: (selected: boolean) => setHasSelection(selected),
    }),
    [],
  )

  const onNativeZoomSettled = useMemo(
    () => ({
      f: (value: number) => setPinchScale(value > 0 ? value : 1),
    }),
    [],
  )

  const downloadSource = useMemo(
    () => (typeof src === 'string' ? { uri: src } : src),
    [src],
  )

  return (
    <View style={[styles.root, { backgroundColor: palette.appBg }, style]}>
      {showToolbar && info ? (
        <DocxToolbar
          fileName={info.fileName || 'Document'}
          page={page}
          pageCount={pageCount}
          zoom={zoom}
          scale={scale}
          pinch={pinchScale}
          isDark={isDark}
          showUpload={showUpload}
          showDownload={showDownload}
          actions={toolbarActions}
          palette={palette}
          onZoomChange={setZoom}
          onToggleDark={setDark}
          onUpload={onRequestUpload}
          onDownload={onRequestDownload ? () => onRequestDownload(downloadSource) : undefined}
          onPrevious={() => goToPage(page - 1)}
          onNext={() => goToPage(page + 1)}
        />
      ) : null}

      {showToolbar && info && (showThumbnails || showOutline || showComments) ? (
        <View style={[styles.tabs, { borderBottomColor: palette.pageBorder }]}>
          {showThumbnails ? (
            <Tab label="Pages" active={panel === 'pages'} palette={palette} onPress={() => setPanel(panel === 'pages' ? 'none' : 'pages')} />
          ) : null}
          {showOutline ? (
            <Tab label="Outline" active={panel === 'outline'} palette={palette} onPress={() => setPanel(panel === 'outline' ? 'none' : 'outline')} />
          ) : null}
          {showComments ? (
            <Tab
              label={`Comments${info.comments.length ? ` (${info.comments.length})` : ''}`}
              active={panel === 'comments'}
              palette={palette}
              onPress={() => setPanel(panel === 'comments' ? 'none' : 'comments')}
            />
          ) : null}
          {onTrackedChangesChange ? (
            <Tab
              label={TRACKED_LABEL[trackedChanges]}
              active={trackedChanges !== 'markup'}
              palette={palette}
              onPress={() =>
                onTrackedChangesChange(
                  trackedChanges === 'markup'
                    ? 'final'
                    : trackedChanges === 'final'
                      ? 'original'
                      : 'markup',
                )
              }
            />
          ) : null}
          {!layoutFinal ? (
            <Tab label="Laying out…" active={false} palette={palette} onPress={() => undefined} />
          ) : null}
        </View>
      ) : null}

      <View style={styles.viewer} onLayout={onLayout}>
        {error ? (
          <ErrorState error={error} palette={palette} />
        ) : loading || !info ? (
          <LoadingState palette={palette} />
        ) : (
          <DocxView
            documentId={engine.id}
            scale={scale}
            scrollToPage={page}
            scrollNonce={nonce}
            palette={palette}
            trackedChanges={trackedChanges}
            onPageChange={onNativePageChange}
            onLayoutProgress={onNativeLayoutProgress}
            onCommentPress={onNativeCommentPress}
            onSelectionChange={onNativeSelection}
            onZoomSettled={onNativeZoomSettled}
            style={styles.native}
          />
        )}

        {hasSelection ? (
          <View style={[styles.toast, { backgroundColor: palette.text }]}>
            <LoadingStateLabel palette={palette} />
          </View>
        ) : null}
      </View>

      {panel === 'pages' && info ? (
        <ThumbnailPanel
          documentId={engine.id}
          pageCount={pageCount}
          pageSize={pageSize}
          currentPage={page}
          palette={palette}
          onSelect={goToPage}
          onClose={() => setPanel('none')}
        />
      ) : null}
      {panel === 'outline' && info ? (
        <OutlinePanel
          outline={info.outline}
          palette={palette}
          onSelect={onSelectOutline}
          onClose={() => setPanel('none')}
        />
      ) : null}
      {panel === 'comments' && info ? (
        <CommentsPanel
          comments={info.comments}
          palette={palette}
          onSelect={onSelectComment}
          onClose={() => setPanel('none')}
        />
      ) : null}
    </View>
  )
})

const TRACKED_LABEL: Record<TrackedChangesMode, string> = {
  markup: 'Changes: markup',
  final: 'Changes: final',
  original: 'Changes: original',
}

function Tab({
  label,
  active,
  palette,
  onPress,
}: {
  label: string
  active: boolean
  palette: { text: string; muted: string; pageBorder: string }
  onPress: () => void
}) {
  return (
    <View
      style={[
        styles.tab,
        active && { backgroundColor: palette.pageBorder },
      ]}
    >
      <Text onPress={onPress} style={[styles.tabText, { color: active ? palette.text : palette.muted }]}>
        {label}
      </Text>
    </View>
  )
}

function LoadingStateLabel({ palette }: { palette: { pageBg: string; text: string } }) {
  return <Text style={[styles.toastText, { color: palette.pageBg }]}>Page text copied</Text>
}

// Local import kept here to avoid a circular import with States.tsx helpers.
import { Text } from 'react-native'

const styles = StyleSheet.create({
  root: {
    flex: 1,
  },
  viewer: {
    flex: 1,
    overflow: 'hidden',
  },
  native: {
    flex: 1,
  },
  tabs: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  tab: {
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRadius: 6,
  },
  tabText: {
    fontSize: 12,
    fontWeight: '600',
  },
  toast: {
    position: 'absolute',
    bottom: 20,
    alignSelf: 'center',
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: 999,
  },
  toastText: {
    fontSize: 12,
    fontWeight: '600',
  },
})
