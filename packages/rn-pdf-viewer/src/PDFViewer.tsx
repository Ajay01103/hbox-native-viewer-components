import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ActivityIndicator,
  Animated,
  PanResponder,
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native'
import type { GestureResponderEvent, LayoutChangeEvent } from 'react-native'
import { FlashList } from '@shopify/flash-list'
import type { FlashListRef, ViewToken } from '@shopify/flash-list'
import { PdfPage } from './PdfPage'
import { usePdfViewer } from './usePdfViewer'
import { resolveScale, rotateSize } from './zoom'
import type { Size } from './zoom'
import type { PDFViewerProps } from './types'

const A4: Size = { width: 612, height: 792 }
const MIN_ZOOM = 0.2
const MAX_ZOOM = 8

function clampScale(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) return min
  return Math.min(Math.max(value, min), max)
}

function touchDistance(event: GestureResponderEvent): number {
  const touches = event.nativeEvent.touches
  if (touches.length < 2) return 0
  const [first, second] = touches
  if (!first || !second) return 0
  return Math.hypot(first.pageX - second.pageX, first.pageY - second.pageY)
}

export function PDFViewer({
  src,
  defaultZoom = 'fit-width',
  showToolbar = true,
  showRotateControls = false,
  renderToolbar,
  toolbarActions,
  renderPageOverlay,
  pageStyle,
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
  const listRef = useRef<FlashListRef<{ page: number }> | null>(null)
  const visiblePage = useRef(1)
  const pageSizes = useRef<Map<number, Size>>(new Map())

  useEffect(() => {
    pageSizes.current.clear()
  }, [document])

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
        const measured = document?.getPageSize(page)
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

  return (
    <View style={styles.root} onLayout={onLayout}>
      {renderToolbar
        ? renderToolbar(api)
        : showToolbar
          ? (
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
                  <TouchableOpacity
                    style={styles.button}
                    onPress={() => api.rotate(1)}
                  >
                    <Text>Rotate</Text>
                  </TouchableOpacity>
                ) : null}
                {toolbarActions}
              </View>
            )
          : null}

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
                  overlay={renderPageOverlay?.({
                    page: item.page,
                    width,
                    height,
                  })}
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
})
