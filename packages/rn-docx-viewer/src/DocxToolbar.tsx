import { Pressable, StyleSheet, Text, View } from 'react-native'
import type { DocxPalette } from '@hbox/react-native-nitro-docx'
import type { Zoom } from './types'
import { zoomLabel } from './zoom'

export interface DocxToolbarProps {
  fileName: string
  page: number
  pageCount: number
  zoom: Zoom
  scale: number
  isDark: boolean
  showUpload?: boolean
  showDownload?: boolean
  actions?: React.ReactNode
  palette: DocxPalette
  onZoomChange: (zoom: Zoom) => void
  onToggleDark: (isDark: boolean) => void
  onUpload?: () => void
  onDownload?: () => void
  onPrevious: () => void
  onNext: () => void
}

/**
 * Text-only toolbar: no icon dependency, so the chrome renders with any Tamagui
 * setup and stays legible at large font scales.
 */
export function DocxToolbar({
  fileName,
  page,
  pageCount,
  zoom,
  scale,
  isDark,
  showUpload = true,
  showDownload = true,
  actions,
  palette,
  onZoomChange,
  onToggleDark,
  onUpload,
  onDownload,
  onPrevious,
  onNext,
}: DocxToolbarProps) {
  return (
    <View style={[styles.root, { backgroundColor: palette.appBg, borderBottomColor: palette.pageBorder }]}>
      <Text style={[styles.fileName, { color: palette.text }]} numberOfLines={1}>
        {fileName}
      </Text>

      <View style={styles.group}>
        <Pressable
          style={[styles.iconButton, { borderColor: palette.pageBorder }]}
          onPress={() => onZoomChange(typeof zoom === 'number' ? Math.max(0.25, zoom - 0.1) : scale)}
          accessibilityLabel="Zoom out"
        >
          <Text style={[styles.icon, { color: palette.text }]}>−</Text>
        </Pressable>
        <Pressable
          style={[styles.zoomLabel, { borderColor: palette.pageBorder }]}
          onPress={() => onZoomChange('fit-width')}
          accessibilityLabel="Cycle zoom mode"
        >
          <Text style={[styles.zoomText, { color: palette.text }]}>
            {zoomLabel(zoom, scale)}
          </Text>
        </Pressable>
        <Pressable
          style={[styles.iconButton, { borderColor: palette.pageBorder }]}
          onPress={() =>
            onZoomChange(typeof zoom === 'number' ? Math.min(4, zoom + 0.1) : scale + 0.1)
          }
          accessibilityLabel="Zoom in"
        >
          <Text style={[styles.icon, { color: palette.text }]}>+</Text>
        </Pressable>
      </View>

      <View style={styles.group}>
        <Pressable
          style={[styles.iconButton, { borderColor: palette.pageBorder }]}
          onPress={onPrevious}
          disabled={page <= 1}
          accessibilityLabel="Previous page"
        >
          <Text style={[styles.icon, { color: page <= 1 ? palette.muted : palette.text }]}>‹</Text>
        </Pressable>
        <Text style={[styles.pageLabel, { color: palette.muted }]}>
          {page} / {pageCount}
        </Text>
        <Pressable
          style={[styles.iconButton, { borderColor: palette.pageBorder }]}
          onPress={onNext}
          disabled={page >= pageCount}
          accessibilityLabel="Next page"
        >
          <Text style={[styles.icon, { color: page >= pageCount ? palette.muted : palette.text }]}>
            ›
          </Text>
        </Pressable>
      </View>

      <View style={styles.group}>
        <Pressable
          style={[styles.textButton, { borderColor: palette.pageBorder }]}
          onPress={() => onToggleDark(!isDark)}
          accessibilityLabel="Toggle dark mode"
        >
          <Text style={[styles.small, { color: palette.text }]}>{isDark ? 'Light' : 'Dark'}</Text>
        </Pressable>
        {showUpload && onUpload ? (
          <Pressable
            style={[styles.textButton, { borderColor: palette.pageBorder }]}
            onPress={onUpload}
            accessibilityLabel="Upload a document"
          >
            <Text style={[styles.small, { color: palette.text }]}>Open</Text>
          </Pressable>
        ) : null}
        {showDownload && onDownload ? (
          <Pressable
            style={[styles.textButton, { borderColor: palette.pageBorder }]}
            onPress={onDownload}
            accessibilityLabel="Download the document"
          >
            <Text style={[styles.small, { color: palette.text }]}>Save</Text>
          </Pressable>
        ) : null}
        {actions}
      </View>
    </View>
  )
}

const styles = StyleSheet.create({
  root: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  fileName: {
    flex: 1,
    fontSize: 13,
    fontWeight: '600',
  },
  group: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
  },
  iconButton: {
    minWidth: 30,
    height: 30,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 6,
    borderWidth: StyleSheet.hairlineWidth,
  },
  icon: {
    fontSize: 16,
    lineHeight: 18,
  },
  zoomLabel: {
    minWidth: 68,
    height: 30,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 6,
    borderWidth: StyleSheet.hairlineWidth,
  },
  zoomText: {
    fontSize: 12,
  },
  pageLabel: {
    fontSize: 12,
    fontVariant: ['tabular-nums'],
    minWidth: 46,
    textAlign: 'center',
  },
  textButton: {
    height: 30,
    paddingHorizontal: 10,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 6,
    borderWidth: StyleSheet.hairlineWidth,
  },
  small: {
    fontSize: 12,
    fontWeight: '600',
  },
})
