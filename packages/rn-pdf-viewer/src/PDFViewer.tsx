import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ActivityIndicator,
  Animated,
  BackHandler,
  PanResponder,
  ScrollView,
  Share,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native'
import type { GestureResponderEvent, LayoutChangeEvent } from 'react-native'
import { FlashList } from '@shopify/flash-list'
import type { FlashListRef, ViewToken } from '@shopify/flash-list'
import { PdfPage } from './PdfPage'
import { PageEditor } from './PageEditor'
import { usePdfViewer } from './usePdfViewer'
import { resolveScale, rotateSize } from './zoom'
import type { Size } from './zoom'
import type { PDFViewerProps, PdfPoint } from './types'
import {
  HIGHLIGHT_COLORS,
  HIGHLIGHT_HEIGHTS,
  INK_COLORS,
  INK_WIDTHS,
  usePdfEditor,
} from './editor'
import {
  ActionRow,
  CommentSheet,
  CommentsSheet,
  PdfBottomBar,
  PdfTopBar,
  SearchSheet,
  SignatureSheet,
  Sheet,
  ToolOptions,
} from './chrome'
import { ICONS } from './icons'

const A4: Size = { width: 612, height: 792 }
const MIN_ZOOM = 0.2
const MAX_ZOOM = 8
const SIGNATURE_WIDTH = 0.36
const SIGNATURE_HEIGHT = 0.18

type SheetName = 'none' | 'edit' | 'more' | 'search' | 'comment' | 'comments' | 'sign'

function clampScale(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) return min
  return Math.min(Math.max(value, min), max)
}

function clamp01(value: number): number {
  if (!Number.isFinite(value)) return 0
  return Math.min(1, Math.max(0, value))
}

function touchDistance(event: GestureResponderEvent): number {
  const touches = event.nativeEvent.touches
  if (touches.length < 2) return 0
  const [first, second] = touches
  if (!first || !second) return 0
  return Math.hypot(first.pageX - second.pageX, first.pageY - second.pageY)
}

/**
 * Single-package PDF viewer and editor.
 *
 * Chrome follows the Acrobat layout: a document app bar (back, read aloud,
 * undo, annotate, search, share, overflow) and a tool strip (edit, comment,
 * highlight, draw, fill & sign, more tools). Marks are captured as normalised
 * page geometry on top of the native page renderer, so they stay attached to
 * the document at every zoom level and can be persisted by the host through
 * `onAnnotationsChange`.
 */
export function PDFViewer({
  src,
  defaultZoom = 'fit-width',
  showToolbar = true,
  chrome = 'acrobat',
  showRotateControls = false,
  renderToolbar,
  toolbarActions,
  renderPageOverlay,
  pageStyle,
  initialAnnotations,
  onAnnotationsChange,
  onBack,
  onShare,
  onReadAloud,
  onActivePageChange,
  onDocumentLoadSuccess,
  onTextSelected,
}: PDFViewerProps) {
  const { api, document, pageCount, loading, error } = usePdfViewer({
    src,
    defaultZoom,
    onActivePageChange,
    onDocumentLoadSuccess,
  })
  const [container, setContainer] = useState<Size>({ width: 0, height: 0 })
  const [sheet, setSheet] = useState<SheetName>('none')
  const [hint, setHint] = useState<string | null>(null)
  const [pendingComment, setPendingComment] = useState<{ page: number; point: PdfPoint } | null>(null)
  const [signatureStrokes, setSignatureStrokes] = useState<PdfPoint[][] | null>(null)
  const [searchQuery, setSearchQuery] = useState('')
  const [searchBusy, setSearchBusy] = useState(false)
  const [searchMessage, setSearchMessage] = useState<string | null>(null)
  const [highlightColor, setHighlightColor] = useState<string>(HIGHLIGHT_COLORS[0])
  const [inkColor, setInkColor] = useState<string>(INK_COLORS[0])
  // 0..2 stop shared by the highlighter band height and the pen width.
  const [sizeStop, setSizeStop] = useState(1)

  const editor = usePdfEditor({ initial: initialAnnotations, onChange: onAnnotationsChange })
  const listRef = useRef<FlashListRef<{ page: number }> | null>(null)
  const visiblePage = useRef(1)
  const pageSizes = useRef<Map<number, Size>>(new Map())
  const hintTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    pageSizes.current.clear()
  }, [document])

  useEffect(
    () => () => {
      if (hintTimer.current) clearTimeout(hintTimer.current)
    },
    [],
  )

  // Sheets are in-tree overlays, so Android's back button must close them.
  useEffect(() => {
    if (sheet === 'none') return
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      setSheet('none')
      return true
    })
    return () => subscription.remove()
  }, [sheet])

  const showHint = useCallback((message: string) => {
    setHint(message)
    if (hintTimer.current) clearTimeout(hintTimer.current)
    hintTimer.current = setTimeout(() => setHint(null), 2800)
  }, [])

  const items = useMemo(
    () => Array.from({ length: pageCount }, (_, index) => ({ page: index + 1 })),
    [pageCount],
  )

  const onLayout = useCallback((event: LayoutChangeEvent) => {
    const { width, height } = event.nativeEvent.layout
    setContainer({ width, height })
  }, [])

  // Page geometry is read once per page and cached: calling into the native
  // document on every FlashList render pass is both slow and racy.
  const readPageSize = useCallback(
    (page: number): Size => {
      const cached = pageSizes.current.get(page)
      if (cached) return cached

      let size: Size = A4
      try {
        // Native geometry calls are 0-based; `page` here is 1-based.
        const measured = document?.getPageSize(Math.max(0, page - 1))
        if (measured && measured.width > 0 && measured.height > 0) {
          size = measured
        }
      } catch {
        // A page that cannot be measured falls back to A4; it must never
        // throw out of render, which would be fatal in a release build.
      }
      pageSizes.current.set(page, size)
      return size
    },
    [document],
  )

  // The page indicator must follow the *current* goTo, not the one captured on
  // mount. A ref keeps the FlashList callback (registered once) up to date;
  // previously it held the first `goTo`, whose pageCount was 0, so every
  // scroll event clamped the page back to 1 and Prev/Next looked broken.
  const goToRef = useRef(api.goTo)
  goToRef.current = api.goTo

  const onViewableItemsChanged = useRef(
    ({ viewableItems }: { viewableItems: Array<ViewToken<{ page: number }>> }) => {
      const first = viewableItems[0]
      if (!first) return
      const page = typeof first.index === 'number' ? first.index + 1 : first.item.page
      visiblePage.current = page
      goToRef.current(page)
    },
  ).current

  useEffect(() => {
    if (visiblePage.current === api.page) return
    visiblePage.current = api.page
    if (api.page > 0 && api.page <= pageCount) {
      // Not animated: an animated jump reports every page in between through
      // `onViewableItemsChanged`, which fights the scroll.
      listRef.current?.scrollToIndex({ index: api.page - 1, animated: false })
    }
  }, [api.page, pageCount])

  const currentPageSize = document ? readPageSize(api.page) : A4
  const fitPageScale = resolveScale('fit-page', currentPageSize, container, api.rotation)
  const currentScale = clampScale(
    resolveScale(api.zoom, currentPageSize, container, api.rotation),
    Math.max(MIN_ZOOM, fitPageScale),
    Math.min(MAX_ZOOM, Math.max(fitPageScale, 1) * 8),
  )

  // Pinch zoom: the whole list is scaled with a native-driven-ish transform
  // while the fingers move, then the new scale is committed to the native
  // renderer once the gesture ends, so the rasterized page is sharp instead of
  // staying a blown-up bitmap.
  const pinchScale = useRef(new Animated.Value(1)).current
  const pinch = useRef({ distance: 0, scale: 1, factor: 1 })

  const commitPinch = useCallback(() => {
    const { factor, scale } = pinch.current
    pinch.current.factor = 1
    pinchScale.setValue(1)
    if (!Number.isFinite(factor) || Math.abs(factor - 1) < 0.02) return
    const next = clampScale(
      scale * factor,
      Math.max(MIN_ZOOM, fitPageScale),
      Math.min(MAX_ZOOM, Math.max(fitPageScale, 1) * 8),
    )
    api.setZoom(Number(next.toFixed(3)))
  }, [api, fitPageScale, pinchScale])

  const pinchResponder = useMemo(
    () =>
      PanResponder.create({
        onStartShouldSetPanResponderCapture: (event) =>
          event.nativeEvent.touches.length >= 2,
        onMoveShouldSetPanResponderCapture: (event) =>
          event.nativeEvent.touches.length >= 2,
        onPanResponderGrant: (event) => {
          pinch.current = {
            distance: touchDistance(event),
            scale: currentScale,
            factor: 1,
          }
        },
        onPanResponderMove: (event) => {
          const start = pinch.current
          if (start.distance <= 0 || start.scale <= 0) return
          const distance = touchDistance(event)
          if (distance <= 0) return
          const next = clampScale(
            start.scale * (distance / start.distance),
            Math.max(MIN_ZOOM, fitPageScale),
            Math.min(MAX_ZOOM, Math.max(fitPageScale, 1) * 8),
          )
          start.factor = next / start.scale
          pinchScale.setValue(start.factor)
        },
        onPanResponderTerminationRequest: () => false,
        onPanResponderRelease: commitPinch,
        onPanResponderTerminate: commitPinch,
      }),
    [commitPinch, currentScale, fitPageScale, pinchScale],
  )

  const runSearch = useCallback(async () => {
    const query = searchQuery.trim()
    if (query.length === 0) return
    setSearchBusy(true)
    setSearchMessage(null)
    try {
      await api.search(query)
    } catch (cause) {
      // Native rejections carry a Java stack trace; a sheet needs one sentence.
      const raw = cause instanceof Error ? cause.message : String(cause)
      setSearchMessage(
        /not implemented/i.test(raw)
          ? 'Search needs the PDF text layer, which this Android build does not expose yet.'
          : raw.split('\n')[0] || 'Search failed',
      )
    } finally {
      setSearchBusy(false)
    }
  }, [api, searchQuery])

  const handleShare = useCallback(async () => {
    if (onShare) {
      onShare(src ?? '')
      return
    }
    try {
      await Share.share({ message: src ?? '' })
    } catch {
      showHint('Nothing to share yet')
    }
  }, [onShare, showHint, src])

  const handleReadAloud = useCallback(() => {
    if (onReadAloud) {
      onReadAloud(api.page)
      return
    }
    showHint('Pass onReadAloud to speak the page text.')
  }, [api.page, onReadAloud, showHint])

  const selectTool = useCallback(
    (tool: 'none' | 'highlight' | 'draw' | 'comment' | 'sign') => {
      if (tool === 'sign' && (!signatureStrokes || signatureStrokes.length === 0)) {
        setSheet('sign')
        return
      }
      editor.setTool(tool)
      setSheet('none')
      if (tool === 'highlight') showHint('Drag across the page to highlight')
      if (tool === 'draw') showHint('Draw on the page with one finger')
      if (tool === 'comment') showHint('Tap the page to drop a comment')
      if (tool === 'sign') showHint('Tap the page to place your signature')
    },
    [editor, showHint, signatureStrokes],
  )

  const placeSignature = useCallback(
    (page: number, point: PdfPoint) => {
      if (!signatureStrokes || signatureStrokes.length === 0) {
        setSheet('sign')
        return
      }
      editor.add({
        page,
        kind: 'signature',
        strokes: signatureStrokes,
        strokeWidth: 2.4,
        color: '#111827',
        rect: {
          x: clamp01(Math.min(point.x, 1 - SIGNATURE_WIDTH)),
          y: clamp01(Math.min(point.y, 1 - SIGNATURE_HEIGHT)),
          width: SIGNATURE_WIDTH,
          height: SIGNATURE_HEIGHT,
        },
      })
      editor.setTool('none')
      showHint('Signature placed')
    },
    [editor, showHint, signatureStrokes],
  )

  const saveComment = useCallback(
    (text: string) => {
      if (!pendingComment) return
      editor.add({
        page: pendingComment.page,
        kind: 'note',
        point: pendingComment.point,
        text,
        color: '#f59e0b',
      })
      setSheet('comments')
      setPendingComment(null)
      setSheet('none')
      showHint('Comment added')
    },
    [editor, pendingComment, showHint],
  )

  const chromeVisible = showToolbar && chrome === 'acrobat'
  const toolOptionsVisible =
    chromeVisible && (editor.tool === 'highlight' || editor.tool === 'draw')
  const commentNotes = useMemo(
    () => editor.annotations.filter((annotation) => annotation.kind === 'note'),
    [editor.annotations],
  )
  const commentCount = commentNotes.length

  return (
    <View style={styles.root} onLayout={onLayout}>
      {renderToolbar ? (
        renderToolbar(api)
      ) : chromeVisible ? (
        <PdfTopBar
          onBack={onBack}
          onReadAloud={handleReadAloud}
          onUndo={editor.undo}
          canUndo={editor.annotations.length > 0}
          onAnnotate={() => setSheet('edit')}
          annotating={editor.tool !== 'none'}
          onSearch={() => setSheet('search')}
          onShare={handleShare}
          onMore={() => setSheet('more')}
        />
      ) : showToolbar ? (
        <View style={styles.toolbar}>
          <TouchableOpacity
            accessibilityRole="button"
            accessibilityLabel="Previous page"
            style={styles.button}
            disabled={api.page <= 1}
            onPress={() => api.goTo(api.page - 1)}
          >
            <Text>Prev</Text>
          </TouchableOpacity>
          <Text style={styles.pageLabel}>
            {api.page} / {pageCount}
          </Text>
          <TouchableOpacity
            accessibilityRole="button"
            accessibilityLabel="Next page"
            style={styles.button}
            disabled={api.page >= pageCount}
            onPress={() => api.goTo(api.page + 1)}
          >
            <Text>Next</Text>
          </TouchableOpacity>
          {showRotateControls ? (
            <TouchableOpacity style={styles.button} onPress={() => api.rotate(1)}>
              <Text>Rotate</Text>
            </TouchableOpacity>
          ) : null}
          {toolbarActions}
        </View>
      ) : null}

      {loading ? (
        <View style={styles.center}>
          <ActivityIndicator />
        </View>
      ) : error ? (
        <View style={styles.center}>
          <Text style={styles.error}>{error.message}</Text>
        </View>
      ) : document ? (
        <Animated.View
          accessibilityLabel="Document pages, pinch to zoom"
          style={[styles.listWrap, { transform: [{ scale: pinchScale }] }]}
          {...pinchResponder.panHandlers}
        >
          <FlashList
            ref={listRef}
            data={items}
            keyExtractor={(item) => String(item.page)}
            contentContainerStyle={styles.listContent}
            extraData={`${editor.annotations.length}|${editor.tool}|${editor.color}|${signatureStrokes?.length ?? 0}`}
            renderItem={({ item }) => {
              const pageSize = readPageSize(item.page)
              const rendered = rotateSize(pageSize, api.rotation)
              const rawScale = resolveScale(api.zoom, pageSize, container, api.rotation)
              // Never hand an infinite/NaN size to the native view: a release
              // build treats a layout exception as fatal.
              const scale =
                Number.isFinite(rawScale) && rawScale > 0 ? Math.min(rawScale, MAX_ZOOM) : 1
              const width = Math.max(1, Math.round(rendered.width * scale))
              const height = Math.max(1, Math.round(rendered.height * scale))

              const page = (
                <PdfPage
                  documentId={document.id}
                  page={item.page}
                  scale={scale}
                  rotation={api.rotation}
                  width={width}
                  height={height}
                  onTextSelected={onTextSelected}
                  overlay={
                    <>
                      {renderPageOverlay?.({ page: item.page, width, height })}
                      <PageEditor
                        page={item.page}
                        width={width}
                        height={height}
                        tool={editor.tool}
                        color={editor.tool === 'draw' ? inkColor : highlightColor}
                        strokeWidth={INK_WIDTHS[sizeStop] ?? 3}
                        highlightHeight={HIGHLIGHT_HEIGHTS[sizeStop] ?? 0.026}
                        annotations={editor.annotations}
                        onAdd={editor.add}
                        onRequestComment={(pageNumber, point) => {
                          setPendingComment({ page: pageNumber, point })
                          setSheet('comment')
                        }}
                        onPlaceSignature={(point) => placeSignature(item.page, point)}
                      />
                    </>
                  }
                  style={pageStyle?.(item.page)}
                />
              )

              // Zoomed in past the viewport? Let the page be panned sideways
              // instead of hiding its right edge.
              return width > container.width ? (
                <ScrollView
                  horizontal
                  bounces={false}
                  showsHorizontalScrollIndicator={false}
                  style={styles.pageScroll}
                >
                  {page}
                </ScrollView>
              ) : (
                <View style={styles.pageCenter}>{page}</View>
              )
            }}
            onViewableItemsChanged={onViewableItemsChanged}
            viewabilityConfig={{ itemVisiblePercentThreshold: 60 }}
          />
        </Animated.View>
      ) : (
        <View style={styles.center}>
          <Text>No document</Text>
        </View>
      )}

      {hint ? (
        <View
          style={[styles.hint, { bottom: toolOptionsVisible ? 164 : 96 }]}
          pointerEvents="none"
        >
          <Text style={styles.hintText}>{hint}</Text>
        </View>
      ) : null}

      {toolOptionsVisible ? (
        <ToolOptions
          label={editor.tool === 'highlight' ? 'Highlighter' : 'Pen'}
          colors={editor.tool === 'highlight' ? HIGHLIGHT_COLORS : INK_COLORS}
          color={editor.tool === 'highlight' ? highlightColor : inkColor}
          onColor={editor.tool === 'highlight' ? setHighlightColor : setInkColor}
          sizeCount={3}
          size={sizeStop}
          onSize={setSizeStop}
        />
      ) : null}

      {chromeVisible ? (
        <PdfBottomBar
          tool={editor.tool}
          commentCount={commentCount}
          onEdit={() => setSheet('edit')}
          onTool={(next) => {
            // Opening the comment tool with existing notes shows the list first,
            // which is the only way to read what was already added.
            if (next === 'comment' && commentCount > 0) {
              setSheet('comments')
              return
            }
            selectTool(next)
          }}
          onMore={() => setSheet('more')}
        />
      ) : null}

      {sheet === 'edit' ? (
        <Sheet title="Edit PDF" onClose={() => setSheet('none')}>
          <ActionRow
            icon={ICONS.highlight}
            label="Highlight"
            active={editor.tool === 'highlight'}
            detail="Drag"
            onPress={() => selectTool('highlight')}
          />
          <ActionRow
            icon={ICONS.draw}
            label="Draw"
            active={editor.tool === 'draw'}
            detail="Freehand"
            onPress={() => selectTool('draw')}
          />
          <ActionRow
            icon={ICONS.comment}
            label="Add comment"
            active={editor.tool === 'comment'}
            detail="Tap"
            onPress={() => selectTool('comment')}
          />
          <ActionRow
            icon={ICONS.sign}
            label="Fill & Sign"
            active={editor.tool === 'sign'}
            onPress={() => setSheet('sign')}
          />
          <ActionRow
            icon={ICONS.undo}
            label="Undo last mark"
            detail={`${editor.annotations.length} mark${editor.annotations.length === 1 ? '' : 's'}`}
            onPress={editor.undo}
          />
          <ActionRow icon={ICONS.trash} label="Clear all marks" onPress={editor.clear} />
          <ActionRow icon={ICONS.rotate} label="Rotate page" onPress={() => api.rotate(1)} />
          <ActionRow icon={ICONS.fitWidth} label="Fit width" onPress={() => api.setZoom('fit-width')} />
          <ActionRow icon={ICONS.fitPage} label="Fit page" onPress={() => api.setZoom('fit-page')} />
        </Sheet>
      ) : null}

      {sheet === 'more' ? (
        <Sheet title="More tools" onClose={() => setSheet('none')}>
          <ActionRow
            icon={ICONS.search}
            label="Search"
            onPress={() => setSheet('search')}
          />
          <ActionRow icon={ICONS.volume} label="Read aloud" onPress={handleReadAloud} />
          <ActionRow icon={ICONS.share} label="Share" onPress={handleShare} />
          <ActionRow
            icon={ICONS.fitWidth}
            label="Fit width"
            detail={`${Math.round(currentScale * 100)}%`}
            onPress={() => api.setZoom('fit-width')}
          />
          <ActionRow icon={ICONS.fitPage} label="Fit page" onPress={() => api.setZoom('fit-page')} />
          <ActionRow icon={ICONS.rotate} label="Rotate page" onPress={() => api.rotate(1)} />
          <ActionRow
            icon={ICONS.trash}
            label="Clear all marks"
            detail={`${editor.annotations.length}`}
            onPress={editor.clear}
          />
        </Sheet>
      ) : null}

      {sheet === 'search' ? (
        <SearchSheet
          query={searchQuery}
          onQueryChange={setSearchQuery}
          onSubmit={runSearch}
          busy={searchBusy}
          count={api.hits.length}
          message={searchMessage}
          onPrevious={api.prevHit}
          onNext={api.nextHit}
          onClose={() => setSheet('none')}
        />
      ) : null}

      {sheet === 'comment' ? (
        <CommentSheet onSave={saveComment} onClose={() => setSheet('none')} />
      ) : null}

      {sheet === 'comments' ? (
        <CommentsSheet
          comments={commentNotes.map((note) => ({
            id: note.id,
            page: note.page,
            text: note.text ?? '',
          }))}
          onJump={(page) => {
            api.goTo(page)
            setSheet('none')
          }}
          onDelete={editor.remove}
          onAdd={() => {
            editor.setTool('comment')
            setSheet('none')
            showHint('Tap the page to drop a comment')
          }}
          onClose={() => setSheet('none')}
        />
      ) : null}

      {sheet === 'sign' ? (
        <SignatureSheet
          onSave={(strokes) => {
            setSignatureStrokes(strokes)
            editor.setTool('sign')
            setSheet('none')
            showHint('Tap the page to place your signature')
          }}
          onClose={() => setSheet('none')}
        />
      ) : null}
    </View>
  )
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#f2f2f2',
  },
  toolbar: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    paddingHorizontal: 12,
    paddingVertical: 8,
    backgroundColor: '#ffffff',
  },
  button: {
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 6,
    backgroundColor: '#e6e6e6',
  },
  pageLabel: {
    fontVariant: ['tabular-nums'],
  },
  listWrap: {
    flex: 1,
  },
  listContent: {
    padding: 12,
  },
  pageScroll: {
    marginBottom: 12,
  },
  pageCenter: {
    alignItems: 'center',
    marginBottom: 12,
  },
  center: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  error: {
    color: '#b00020',
    paddingHorizontal: 24,
    textAlign: 'center',
  },
  hint: {
    position: 'absolute',
    left: 24,
    right: 24,
    bottom: 92,
    alignItems: 'center',
  },
  hintText: {
    backgroundColor: 'rgba(17,24,39,0.92)',
    color: '#ffffff',
    fontSize: 12.5,
    fontWeight: '600',
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: 999,
    overflow: 'hidden',
    textAlign: 'center',
  },
})
