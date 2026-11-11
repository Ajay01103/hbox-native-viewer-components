import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native'
import { ActivityIndicator } from 'react-native'
import type { DocxViewerError } from './types'

export interface LoadingStateProps {
  label?: string
  palette: { pageBg: string; text: string; muted: string }
}

/** Shown while the native parser and first pages are still running. */
export function LoadingState({ label = 'Opening document…', palette }: LoadingStateProps) {
  return (
    <View style={[styles.center, { backgroundColor: palette.pageBg }]}>
      <ActivityIndicator />
      <Text style={[styles.muted, { color: palette.muted }]}>{label}</Text>
    </View>
  )
}

export interface ErrorStateProps {
  error: DocxViewerError
  palette: { pageBg: string; text: string; muted: string }
  onRetry?: () => void
}

const TITLES: Record<DocxViewerError['code'], string> = {
  LEGACY_DOC: 'Legacy .doc file',
  CORRUPT: 'This document could not be read',
  UNSUPPORTED: 'This document uses unsupported features',
  TOO_LARGE: 'This document is too large',
  UNKNOWN: 'Something went wrong',
}

const HINTS: Partial<Record<DocxViewerError['code'], string>> = {
  LEGACY_DOC:
    'Only .docx is supported. Open the file in Word or LibreOffice and save it as .docx, then try again.',
  TOO_LARGE: 'Documents above the size limit are rejected to protect the device.',
}

export function ErrorState({ error, palette, onRetry }: ErrorStateProps) {
  return (
    <ScrollView contentContainerStyle={[styles.center, { backgroundColor: palette.pageBg }]}>
      <Text style={[styles.title, { color: palette.text }]}>{TITLES[error.code]}</Text>
      <Text style={[styles.muted, { color: palette.muted }]}>{HINTS[error.code] ?? error.message}</Text>
      {onRetry ? (
        <Pressable style={styles.retry} onPress={onRetry} accessibilityRole="button">
          <Text style={styles.retryText}>Try again</Text>
        </Pressable>
      ) : null}
    </ScrollView>
  )
}

const styles = StyleSheet.create({
  center: {
    flexGrow: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 10,
    padding: 32,
  },
  title: {
    fontSize: 16,
    fontWeight: '600',
    textAlign: 'center',
  },
  muted: {
    fontSize: 13,
    lineHeight: 19,
    textAlign: 'center',
  },
  retry: {
    marginTop: 8,
    paddingHorizontal: 16,
    paddingVertical: 9,
    borderRadius: 8,
    backgroundColor: '#3b82f6',
  },
  retryText: {
    color: '#ffffff',
    fontWeight: '600',
    fontSize: 13,
  },
})
