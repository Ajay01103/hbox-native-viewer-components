import { useEffect, useMemo, useRef, useState } from 'react'
import {
  Animated,
  Easing,
  Keyboard,
  PanResponder,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native'
import Svg, { Path } from 'react-native-svg'
import { ICONS, Icon } from './icons'
import { inkPath } from './editor'
import type { PdfPoint, PdfTool } from './editor'

const INK = '#111827'
const MUTED = '#6b7280'
const LINE = '#e5e7eb'
const ACCENT = '#1f6feb'
const BAR = '#ffffff'

function ToolbarButton({
  path,
  label,
  onPress,
  active,
  disabled,
  badge,
}: {
  path: string
  label: string
  onPress?: () => void
  active?: boolean
  disabled?: boolean
  badge?: boolean
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ disabled: Boolean(disabled), selected: Boolean(active) }}
      disabled={disabled}
      onPress={onPress}
      hitSlop={6}
      style={({ pressed }) => [styles.iconButton, pressed && styles.pressed]}
    >
      <Icon path={path} size={23} color={disabled ? '#c7cbd4' : active ? ACCENT : INK} />
      {badge ? <View style={styles.badge} /> : null}
    </Pressable>
  )
}

export interface PdfTopBarProps {
  onBack?: () => void
  onReadAloud?: () => void
  onUndo?: () => void
  canUndo: boolean
  onAnnotate?: () => void
  annotating: boolean
  onSearch?: () => void
  onShare?: () => void
  onMore?: () => void
}

/** Acrobat-style document app bar: the doc-level actions, no title. */
export function PdfTopBar({
  onBack,
  onReadAloud,
  onUndo,
  canUndo,
  onAnnotate,
  annotating,
  onSearch,
  onShare,
  onMore,
}: PdfTopBarProps) {
  return (
    <View style={styles.topBar}>
      <ToolbarButton path={ICONS.back} label="Back" onPress={onBack} />
      <View style={styles.topActions}>
        <ToolbarButton path={ICONS.volume} label="Read aloud" onPress={onReadAloud} />
        <ToolbarButton path={ICONS.undo} label="Undo last mark" onPress={onUndo} disabled={!canUndo} />
        <ToolbarButton path={ICONS.annotate} label="Annotate" onPress={onAnnotate} active={annotating} />
        <ToolbarButton path={ICONS.search} label="Search document" onPress={onSearch} />
        <ToolbarButton path={ICONS.share} label="Share document" onPress={onShare} />
        <ToolbarButton path={ICONS.more} label="More options" onPress={onMore} />
      </View>
    </View>
  )
}

export interface PdfBottomBarProps {
  tool: PdfTool
  commentCount: number
  onEdit: () => void
  onTool: (tool: PdfTool) => void
  onMore: () => void
}

const BOTTOM_ITEMS: Array<{
  key: 'edit' | PdfTool | 'more'
  label: string
  icon: string
}> = [
  { key: 'edit', label: 'Edit PDF', icon: ICONS.editPdf },
  { key: 'comment', label: 'Comment', icon: ICONS.comment },
  { key: 'highlight', label: 'Highlight', icon: ICONS.highlight },
  { key: 'draw', label: 'Draw', icon: ICONS.draw },
  { key: 'sign', label: 'Fill & Sign', icon: ICONS.sign },
  { key: 'more', label: 'More tools', icon: ICONS.grid },
]

/** Acrobat-style tool strip. The selected tool stays armed until changed. */
export function PdfBottomBar({ tool, commentCount, onEdit, onTool, onMore }: PdfBottomBarProps) {
  return (
    <View style={styles.bottomBar}>
      {BOTTOM_ITEMS.map((item) => {
        const active = item.key === tool
        return (
          <Pressable
            key={item.key}
            accessibilityRole="button"
            accessibilityLabel={item.label}
            accessibilityState={{ selected: active }}
            style={({ pressed }) => [styles.bottomItem, pressed && styles.pressedItem]}
            onPress={() => {
              if (item.key === 'edit') onEdit()
              else if (item.key === 'more') onMore()
              else onTool(item.key as PdfTool)
            }}
          >
            <View style={[styles.bottomIconWrap, active && styles.bottomIconActive]}>
              <Icon path={item.icon} size={24} color={active ? ACCENT : INK} />
              {item.key === 'edit' ? <View style={styles.badge} /> : null}
              {item.key === 'comment' && commentCount > 0 ? (
                <View style={styles.count}>
                  <Text style={styles.countText}>{commentCount > 9 ? '9+' : commentCount}</Text>
                </View>
              ) : null}
            </View>
            <Text style={[styles.bottomLabel, active && { color: ACCENT }]} numberOfLines={1}>
              {item.label}
            </Text>
          </Pressable>
        )
      })}
    </View>
  )
}

/**
 * In-tree bottom sheet rather than a native `Modal`: a Modal is its own window
 * on Android, so the activity's `adjustResize` does not apply and the keyboard
 * covered the comment composer. As an absolute overlay the sheet rides above it.
 */
export function Sheet({
  title,
  onClose,
  children,
}: {
  title: string
  onClose: () => void
  children: React.ReactNode
}) {
  // Edge-to-edge Android windows no longer resize for `adjustResize`, so the
  // keyboard height is tracked explicitly and lifts the sheet above it.
  const [keyboard, setKeyboard] = useState(0)
  const slide = useRef(new Animated.Value(1)).current

  useEffect(() => {
    const show = Keyboard.addListener('keyboardDidShow', (event) =>
      setKeyboard(event.endCoordinates.height),
    )
    const hide = Keyboard.addListener('keyboardDidHide', () => setKeyboard(0))
    return () => {
      show.remove()
      hide.remove()
    }
  }, [])

  useEffect(() => {
    Animated.timing(slide, {
      toValue: 0,
      duration: 200,
      easing: Easing.out(Easing.cubic),
      useNativeDriver: true,
    }).start()
  }, [slide])

  return (
    <View style={[styles.overlay, keyboard > 0 && { paddingBottom: keyboard }]} pointerEvents="box-none">
      <Pressable style={styles.backdrop} onPress={onClose} accessibilityLabel="Close" />
      <Animated.View
        style={[styles.sheet, { transform: [{ translateY: Animated.multiply(slide, 260) }] }]}
      >
        <View style={styles.sheetHeader}>
          <Text style={styles.sheetTitle}>{title}</Text>
          <Pressable onPress={onClose} accessibilityLabel="Close" hitSlop={12}>
            <Icon path={ICONS.close} size={20} color={MUTED} />
          </Pressable>
        </View>
        {children}
      </Animated.View>
    </View>
  )
}

export interface ToolOptionsProps {
  colors: readonly string[]
  color: string
  onColor: (color: string) => void
  /** Number of size stops, low to high. */
  sizeCount: number
  size: number
  onSize: (size: number) => void
  label: string
}

/** Colour and size picker for the armed drawing tool. */
export function ToolOptions({
  colors,
  color,
  onColor,
  sizeCount,
  size,
  onSize,
  label,
}: ToolOptionsProps) {
  return (
    <View style={styles.optionsBar}>
      <Text style={styles.optionsLabel}>{label}</Text>
      <View style={styles.swatches}>
        {colors.map((option) => (
          <Pressable
            key={option}
            accessibilityRole="button"
            accessibilityLabel={`Colour ${option}`}
            accessibilityState={{ selected: option === color }}
            onPress={() => onColor(option)}
            style={[
              styles.swatch,
              { backgroundColor: option },
              option === color && styles.swatchActive,
            ]}
          />
        ))}
      </View>
      <View style={styles.optionsDivider} />
      <View style={styles.sizes}>
        {Array.from({ length: sizeCount }, (_, index) => (
          <Pressable
            key={index}
            accessibilityRole="button"
            accessibilityLabel={`Size ${index + 1}`}
            accessibilityState={{ selected: index === size }}
            onPress={() => onSize(index)}
            style={[styles.sizeButton, index === size && styles.sizeButtonActive]}
          >
            <View
              style={[
                styles.sizeDot,
                { width: 5 + index * 3.5, height: 5 + index * 3.5, backgroundColor: color },
              ]}
            />
          </Pressable>
        ))}
      </View>
    </View>
  )
}

export function ActionRow({
  icon,
  label,
  detail,
  onPress,
  active,
}: {
  icon: string
  label: string
  detail?: string
  onPress: () => void
  active?: boolean
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      style={({ pressed }) => [styles.row, pressed && styles.pressed]}
      onPress={onPress}
    >
      <Icon path={icon} size={20} color={active ? ACCENT : INK} />
      <Text style={[styles.rowLabel, active && { color: ACCENT }]}>{label}</Text>
      {detail ? <Text style={styles.rowDetail}>{detail}</Text> : null}
    </Pressable>
  )
}

export function CommentsSheet({
  comments,
  onJump,
  onDelete,
  onAdd,
  onClose,
}: {
  comments: Array<{ id: string; page: number; text: string }>
  onJump: (page: number) => void
  onDelete: (id: string) => void
  onAdd: () => void
  onClose: () => void
}) {
  return (
    <Sheet title={`Comments${comments.length ? ` (${comments.length})` : ''}`} onClose={onClose}>
      {comments.length === 0 ? (
        <Text style={styles.sheetHint}>
          No comments yet. Use the Comment tool, then tap the page to drop one.
        </Text>
      ) : (
        comments.map((comment) => (
          <View key={comment.id} style={styles.commentRow}>
            <Pressable
              style={styles.commentTap}
              accessibilityRole="button"
              accessibilityLabel={`Go to comment on page ${comment.page}`}
              onPress={() => onJump(comment.page)}
            >
              <Text style={styles.commentText}>{comment.text}</Text>
              <Text style={styles.commentMeta}>page {comment.page}</Text>
            </Pressable>
            <Pressable
              accessibilityLabel="Delete comment"
              hitSlop={10}
              onPress={() => onDelete(comment.id)}
              style={styles.commentDelete}
            >
              <Icon path={ICONS.trash} size={18} color={MUTED} />
            </Pressable>
          </View>
        ))
      )}
      <Pressable style={styles.primaryButtonWide} onPress={onAdd} accessibilityLabel="Add a comment">
        <Text style={styles.primaryText}>Add a comment</Text>
      </Pressable>
    </Sheet>
  )
}

export interface SearchSheetProps {
  query: string
  onQueryChange: (value: string) => void
  onSubmit: () => void
  busy: boolean
  count: number
  message: string | null
  onPrevious: () => void
  onNext: () => void
  onClose: () => void
}

export function SearchSheet({
  query,
  onQueryChange,
  onSubmit,
  busy,
  count,
  message,
  onPrevious,
  onNext,
  onClose,
}: SearchSheetProps) {
  return (
    <Sheet title="Search" onClose={onClose}>
      <View style={styles.searchRow}>
        <TextInput
          style={styles.searchInput}
          value={query}
          onChangeText={onQueryChange}
          onSubmitEditing={onSubmit}
          placeholder="Find in document"
          placeholderTextColor="#9ca3af"
          returnKeyType="search"
          autoFocus
        />
        <Pressable style={styles.primaryButton} onPress={onSubmit} accessibilityLabel="Run search">
          <Text style={styles.primaryText}>{busy ? '…' : 'Find'}</Text>
        </Pressable>
      </View>
      <Text style={[styles.searchStatus, message ? { color: '#b00020' } : null]}>
        {message ?? (count > 0 ? `${count} match${count === 1 ? '' : 'es'}` : 'Type to search the text layer.')}
      </Text>
      <View style={styles.searchNav}>
        <Pressable
          style={styles.secondaryButton}
          onPress={onPrevious}
          disabled={count === 0}
          accessibilityLabel="Previous match"
        >
          <Icon path={ICONS.chevronLeft} size={18} color={count === 0 ? '#c7cbd4' : INK} />
        </Pressable>
        <Pressable
          style={styles.secondaryButton}
          onPress={onNext}
          disabled={count === 0}
          accessibilityLabel="Next match"
        >
          <Icon path={ICONS.chevronRight} size={18} color={count === 0 ? '#c7cbd4' : INK} />
        </Pressable>
      </View>
    </Sheet>
  )
}

export function CommentSheet({
  onSave,
  onClose,
}: {
  onSave: (text: string) => void
  onClose: () => void
}) {
  const [text, setText] = useState('')
  return (
    <Sheet title="Add comment" onClose={onClose}>
      <TextInput
        style={styles.commentInput}
        value={text}
        onChangeText={setText}
        placeholder="Write your comment"
        placeholderTextColor="#9ca3af"
        multiline
        autoFocus
      />
      <View style={styles.footerRow}>
        <Pressable style={styles.secondaryButtonWide} onPress={onClose} accessibilityLabel="Cancel">
          <Text style={styles.secondaryText}>Cancel</Text>
        </Pressable>
        <Pressable
          style={[styles.primaryButton, text.trim().length === 0 && { opacity: 0.5 }]}
          disabled={text.trim().length === 0}
          onPress={() => onSave(text.trim())}
          accessibilityLabel="Save comment"
        >
          <Text style={styles.primaryText}>Add</Text>
        </Pressable>
      </View>
    </Sheet>
  )
}

function SignaturePad({ onSave }: { onSave: (strokes: PdfPoint[][]) => void }) {
  const [size, setSize] = useState({ width: 0, height: 0 })
  const [strokes, setStrokes] = useState<PdfPoint[][]>([])
  const [draft, setDraft] = useState<PdfPoint[]>([])

  const responder = useMemo(() => {
    const point = (x: number, y: number): PdfPoint => ({
      x: size.width > 0 ? Math.min(1, Math.max(0, x / size.width)) : 0,
      y: size.height > 0 ? Math.min(1, Math.max(0, y / size.height)) : 0,
    })
    return PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onMoveShouldSetPanResponder: () => true,
      onPanResponderGrant: (event) =>
        setDraft([point(event.nativeEvent.locationX, event.nativeEvent.locationY)]),
      onPanResponderMove: (event) =>
        setDraft((current) => [...current, point(event.nativeEvent.locationX, event.nativeEvent.locationY)]),
      onPanResponderRelease: () => {
        setDraft((current) => {
          if (current.length > 1) setStrokes((previous) => [...previous, current])
          return []
        })
      },
      onPanResponderTerminationRequest: () => false,
    })
  }, [size.height, size.width])

  return (
    <View>
      <View
        style={styles.pad}
        onLayout={(event) =>
          setSize({
            width: event.nativeEvent.layout.width,
            height: event.nativeEvent.layout.height,
          })
        }
        {...responder.panHandlers}
      >
        <Svg width={size.width} height={size.height}>
          {strokes.map((stroke, index) => (
            <Path
              key={index}
              d={inkPath([stroke], size.width, size.height)}
              stroke={INK}
              strokeWidth={2.4}
              fill="none"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          ))}
          {draft.length > 1 ? (
            <Path
              d={inkPath([draft], size.width, size.height)}
              stroke={INK}
              strokeWidth={2.4}
              fill="none"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          ) : null}
        </Svg>
        {strokes.length === 0 && draft.length === 0 ? (
          <Text style={styles.padHint}>Sign here with your finger</Text>
        ) : null}
      </View>
      <View style={styles.footerRow}>
        <Pressable
          style={styles.secondaryButtonWide}
          onPress={() => {
            setStrokes([])
            setDraft([])
          }}
          accessibilityLabel="Clear signature"
        >
          <Text style={styles.secondaryText}>Clear</Text>
        </Pressable>
        <Pressable
          style={[styles.primaryButton, strokes.length === 0 && { opacity: 0.5 }]}
          disabled={strokes.length === 0}
          onPress={() => onSave(strokes)}
          accessibilityLabel="Use signature"
        >
          <Text style={styles.primaryText}>Use signature</Text>
        </Pressable>
      </View>
    </View>
  )
}

export function SignatureSheet({
  onSave,
  onClose,
}: {
  onSave: (strokes: PdfPoint[][]) => void
  onClose: () => void
}) {
  return (
    <Sheet title="Fill & Sign" onClose={onClose}>
      <Text style={styles.sheetHint}>
        Draw your signature, then tap the page where it should be placed.
      </Text>
      <SignaturePad onSave={onSave} />
    </Sheet>
  )
}

const styles = StyleSheet.create({
  topBar: {
    flexDirection: 'row',
    alignItems: 'center',
    height: 56,
    paddingHorizontal: 6,
    backgroundColor: BAR,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: LINE,
    elevation: 3,
    shadowColor: '#0f172a',
    shadowOpacity: 0.08,
    shadowRadius: 6,
    shadowOffset: { width: 0, height: 2 },
    zIndex: 2,
  },
  topActions: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingLeft: 6,
  },
  iconButton: {
    width: 42,
    height: 42,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 21,
  },
  pressed: {
    opacity: 0.55,
  },
  badge: {
    position: 'absolute',
    top: 6,
    right: 6,
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: '#7c3aed',
  },
  count: {
    position: 'absolute',
    top: 2,
    right: 2,
    minWidth: 15,
    height: 15,
    paddingHorizontal: 3,
    borderRadius: 8,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#f97316',
  },
  countText: {
    color: '#ffffff',
    fontSize: 9,
    fontWeight: '700',
  },
  bottomBar: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingTop: 8,
    paddingBottom: 6,
    paddingHorizontal: 4,
    backgroundColor: BAR,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: LINE,
    elevation: 10,
    shadowColor: '#0f172a',
    shadowOpacity: 0.1,
    shadowRadius: 10,
    shadowOffset: { width: 0, height: -2 },
    zIndex: 2,
  },
  bottomItem: {
    flex: 1,
    alignItems: 'center',
    gap: 4,
    paddingVertical: 2,
  },
  pressedItem: {
    transform: [{ scale: 0.94 }],
    opacity: 0.85,
  },
  bottomIconWrap: {
    width: 42,
    height: 32,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 10,
  },
  bottomIconActive: {
    backgroundColor: '#e8efff',
  },
  bottomLabel: {
    fontSize: 10.5,
    fontWeight: '600',
    color: INK,
  },
  overlay: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    justifyContent: 'flex-end',
    zIndex: 10,
    elevation: 12,
  },
  backdrop: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: 'rgba(15,23,42,0.35)',
  },
  sheet: {
    backgroundColor: '#ffffff',
    borderTopLeftRadius: 18,
    borderTopRightRadius: 18,
    paddingHorizontal: 18,
    paddingTop: 14,
    paddingBottom: 26,
    gap: 10,
    maxHeight: '82%',
  },
  sheetHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  sheetTitle: {
    fontSize: 16,
    fontWeight: '700',
    color: INK,
  },
  sheetHint: {
    fontSize: 12.5,
    color: MUTED,
    lineHeight: 18,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingVertical: 12,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: LINE,
  },
  rowLabel: {
    flex: 1,
    fontSize: 14.5,
    color: INK,
    fontWeight: '500',
  },
  rowDetail: {
    fontSize: 12,
    color: MUTED,
    fontVariant: ['tabular-nums'],
  },
  searchRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  searchInput: {
    flex: 1,
    height: 42,
    paddingHorizontal: 12,
    borderRadius: 10,
    backgroundColor: '#f3f4f6',
    color: INK,
    fontSize: 14,
  },
  searchStatus: {
    fontSize: 12.5,
    color: MUTED,
    minHeight: 18,
  },
  searchNav: {
    flexDirection: 'row',
    gap: 8,
  },
  commentInput: {
    minHeight: 96,
    padding: 12,
    borderRadius: 10,
    backgroundColor: '#f3f4f6',
    color: INK,
    fontSize: 14,
    textAlignVertical: 'top',
  },
  primaryButton: {
    minWidth: 84,
    height: 42,
    paddingHorizontal: 18,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: ACCENT,
  },
  primaryText: {
    color: '#ffffff',
    fontWeight: '700',
    fontSize: 14,
  },
  secondaryButton: {
    flex: 1,
    height: 40,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#f3f4f6',
  },
  secondaryButtonWide: {
    flex: 1,
    height: 42,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#f3f4f6',
  },
  secondaryText: {
    color: INK,
    fontWeight: '600',
    fontSize: 14,
  },
  footerRow: {
    flexDirection: 'row',
    gap: 10,
  },
  pad: {
    height: 150,
    borderRadius: 12,
    borderWidth: 1,
    borderStyle: 'dashed',
    borderColor: '#c7cbd4',
    backgroundColor: '#fbfbfd',
    overflow: 'hidden',
    alignItems: 'center',
    justifyContent: 'center',
  },
  padHint: {
    position: 'absolute',
    color: '#9ca3af',
    fontSize: 13,
  },
  optionsBar: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingHorizontal: 14,
    paddingVertical: 8,
    backgroundColor: BAR,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: LINE,
  },
  optionsLabel: {
    fontSize: 10.5,
    fontWeight: '700',
    color: MUTED,
    letterSpacing: 0.7,
    textTransform: 'uppercase',
    minWidth: 52,
  },
  swatches: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    flex: 1,
  },
  swatch: {
    width: 22,
    height: 22,
    borderRadius: 11,
    borderWidth: 2,
    borderColor: '#ffffff',
    elevation: 2,
  },
  swatchActive: {
    borderColor: ACCENT,
    transform: [{ scale: 1.15 }],
  },
  optionsDivider: {
    width: 1,
    height: 22,
    backgroundColor: LINE,
  },
  sizes: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
  },
  sizeButton: {
    width: 30,
    height: 30,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 9,
  },
  sizeButtonActive: {
    backgroundColor: '#e8efff',
  },
  sizeDot: {
    borderRadius: 99,
  },
  primaryButtonWide: {
    marginTop: 10,
    height: 44,
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: ACCENT,
  },
  commentRow: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: 8,
    paddingVertical: 10,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: LINE,
  },
  commentTap: {
    flex: 1,
    gap: 2,
  },
  commentText: {
    fontSize: 14,
    color: INK,
    lineHeight: 19,
  },
  commentMeta: {
    fontSize: 11.5,
    color: MUTED,
  },
  commentDelete: {
    padding: 4,
  },
})

export const CHROME_COLORS = { INK, MUTED, LINE, ACCENT, BAR }
