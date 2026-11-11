import { StyleSheet, View } from 'react-native'
import type { ReactNode } from 'react'
import type { StyleProp, ViewStyle } from 'react-native'
import { PdfPageView } from '@hbox/react-native-nitro-pdf'
import type { Rotation } from './types'

export interface PdfPageProps {
  documentId: string
  page: number
  scale: number
  rotation: Rotation
  /** Rendered page size in pixels (already scaled and rotated). */
  width: number
  height: number
  highlights?: number[]
  onTextSelected?: (text: string) => void
  overlay?: ReactNode
  style?: StyleProp<ViewStyle>
}

export function PdfPage({
  documentId,
  page,
  scale,
  rotation,
  width,
  height,
  highlights,
  onTextSelected,
  overlay,
  style,
}: PdfPageProps) {
  return (
    <View style={[styles.page, { width, height }, style]}>
      <PdfPageView
        style={styles.surface}
        documentId={documentId}
        page={page}
        scale={scale}
        rotation={rotation}
        highlights={highlights ?? []}
        onTextSelected={onTextSelected ? { f: onTextSelected } : undefined}
      />
      {overlay ? (
        <View style={StyleSheet.absoluteFill} pointerEvents="box-none">
          {overlay}
        </View>
      ) : null}
    </View>
  )
}

const styles = StyleSheet.create({
  page: {
    overflow: 'hidden',
    backgroundColor: '#ffffff',
  },
  surface: {
    flex: 1,
  },
})
