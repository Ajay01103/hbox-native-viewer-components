import { useCallback, useMemo } from 'react'
import { FlatList, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native'
import { DocxThumbnail } from '@hbox/react-native-nitro-docx'
import type { DocComment, DocxPalette, OutlineItem, PageSize } from '@hbox/react-native-nitro-docx'

const PANEL_BG = { light: '#ffffff', dark: '#232326' }

export interface PanelShellProps {
  title: string
  palette: DocxPalette
  onClose: () => void
  children: React.ReactNode
}

/** Lightweight overlay panel: no Tamagui Sheet/portal dependency. */
export function PanelShell({ title, palette, onClose, children }: PanelShellProps) {
  return (
    <View style={styles.overlay}>
      <Pressable style={styles.backdrop} onPress={onClose} accessibilityLabel="Close panel" />
      <View
        style={[
          styles.panel,
          {
            backgroundColor: palette.pageDarkMode ? PANEL_BG.dark : PANEL_BG.light,
            borderColor: palette.pageBorder,
          },
        ]}
      >
        <View style={[styles.header, { borderBottomColor: palette.pageBorder }]}>
          <Text style={[styles.title, { color: palette.text }]}>{title}</Text>
          <Pressable onPress={onClose} accessibilityLabel="Close">
            <Text style={[styles.close, { color: palette.muted }]}>✕</Text>
          </Pressable>
        </View>
        {children}
      </View>
    </View>
  )
}

export interface ThumbnailPanelProps {
  documentId: string
  pageCount: number
  pageSize: PageSize
  currentPage: number
  palette: DocxPalette
  onSelect: (page: number) => void
  onClose: () => void
}

export function ThumbnailPanel({
  documentId,
  pageCount,
  pageSize,
  currentPage,
  palette,
  onSelect,
  onClose,
}: ThumbnailPanelProps) {
  const width = 112
  const height = useMemo(
    () => (pageSize.width > 0 ? Math.round((pageSize.height / pageSize.width) * width) : 155),
    [pageSize, width],
  )
  const pages = useMemo(
    () => Array.from({ length: pageCount }, (_, index) => index),
    [pageCount],
  )
  const scale = useMemo(
    () => (pageSize.width > 0 ? width / pageSize.width : 0.2),
    [pageSize, width],
  )

  const renderItem = useCallback(
    ({ item }: { item: number }) => (
      <Pressable
        onPress={() => onSelect(item + 1)}
        style={[
          styles.thumb,
          {
            borderColor: item + 1 === currentPage ? '#3b82f6' : palette.pageBorder,
            width: width + 10,
          },
        ]}
      >
        <DocxThumbnail
          documentId={documentId}
          page={item}
          scale={scale}
          palette={palette}
          style={{ width, height }}
        />
        <Text style={[styles.thumbLabel, { color: palette.muted }]}>{item + 1}</Text>
      </Pressable>
    ),
    [currentPage, documentId, height, onSelect, palette, scale, width],
  )

  return (
    <PanelShell title="Pages" palette={palette} onClose={onClose}>
      <FlatList
        data={pages}
        keyExtractor={(item) => String(item)}
        renderItem={renderItem}
        numColumns={2}
        contentContainerStyle={styles.grid}
        columnWrapperStyle={styles.row}
      />
    </PanelShell>
  )
}

export interface OutlinePanelProps {
  outline: OutlineItem[]
  palette: DocxPalette
  onSelect: (item: OutlineItem) => void
  onClose: () => void
}

export function OutlinePanel({ outline, palette, onSelect, onClose }: OutlinePanelProps) {
  return (
    <PanelShell title="Outline" palette={palette} onClose={onClose}>
      <ScrollView contentContainerStyle={styles.list}>
        {outline.length === 0 ? (
          <Text style={[styles.empty, { color: palette.muted }]}>
            This document has no headings.
          </Text>
        ) : (
          outline.map((item) => (
            <Pressable
              key={`${item.id}-${item.level}`}
              onPress={() => onSelect(item)}
              style={[styles.rowItem, { paddingLeft: 12 + (item.level - 1) * 14 }]}
            >
              <Text style={[styles.rowText, { color: palette.text }]} numberOfLines={2}>
                {item.title}
              </Text>
              <Text style={[styles.rowPage, { color: palette.muted }]}>{item.page}</Text>
            </Pressable>
          ))
        )}
      </ScrollView>
    </PanelShell>
  )
}

export interface CommentsPanelProps {
  comments: DocComment[]
  palette: DocxPalette
  onSelect: (comment: DocComment) => void
  onClose: () => void
}

export function CommentsPanel({ comments, palette, onSelect, onClose }: CommentsPanelProps) {
  return (
    <PanelShell title="Comments" palette={palette} onClose={onClose}>
      <ScrollView contentContainerStyle={styles.list}>
        {comments.length === 0 ? (
          <Text style={[styles.empty, { color: palette.muted }]}>
            This document has no comments.
          </Text>
        ) : (
          comments.map((comment) => (
            <Pressable
              key={comment.id}
              onPress={() => onSelect(comment)}
              style={[styles.comment, { borderColor: palette.pageBorder }]}
            >
              <Text style={[styles.commentAuthor, { color: palette.text }]}>
                {comment.author || 'Unknown'}
                {comment.date ? ` · ${comment.date.slice(0, 10)}` : ''}
              </Text>
              <Text style={[styles.rowText, { color: palette.text }]}>{comment.text}</Text>
              <Text style={[styles.rowPage, { color: palette.muted }]}>page {comment.page}</Text>
            </Pressable>
          ))
        )}
      </ScrollView>
    </PanelShell>
  )
}

const styles = StyleSheet.create({
  overlay: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    justifyContent: 'flex-start',
  },
  backdrop: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: 'rgba(0,0,0,0.25)',
  },
  panel: {
    marginTop: 88,
    marginHorizontal: 16,
    maxHeight: '70%',
    borderRadius: 12,
    borderWidth: StyleSheet.hairlineWidth,
    overflow: 'hidden',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 14,
    paddingVertical: 10,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  title: {
    fontSize: 14,
    fontWeight: '700',
  },
  close: {
    fontSize: 16,
    paddingHorizontal: 4,
  },
  grid: {
    padding: 10,
    gap: 10,
  },
  row: {
    gap: 10,
  },
  thumb: {
    alignItems: 'center',
    gap: 4,
    padding: 4,
    borderRadius: 8,
    borderWidth: 2,
  },
  thumbLabel: {
    fontSize: 11,
  },
  list: {
    paddingVertical: 6,
  },
  empty: {
    padding: 20,
    fontSize: 13,
    textAlign: 'center',
  },
  rowItem: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingVertical: 9,
    paddingRight: 14,
  },
  rowText: {
    flex: 1,
    fontSize: 13,
    lineHeight: 18,
  },
  rowPage: {
    fontSize: 11,
    fontVariant: ['tabular-nums'],
  },
  comment: {
    marginHorizontal: 10,
    marginVertical: 5,
    padding: 10,
    gap: 4,
    borderRadius: 8,
    borderWidth: StyleSheet.hairlineWidth,
  },
  commentAuthor: {
    fontSize: 12,
    fontWeight: '700',
  },
})
