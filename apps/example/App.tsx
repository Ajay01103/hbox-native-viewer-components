import { Component, useCallback, useRef, useState } from 'react'
import type { ErrorInfo, ReactNode } from 'react'
import { ActivityIndicator, NativeModules, Pressable, Share, StyleSheet, Text, View } from 'react-native'
import { StatusBar } from 'expo-status-bar'
import * as DocumentPicker from 'expo-document-picker'
import { File, Paths } from 'expo-file-system'
import { TamaguiProvider } from 'tamagui'
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context'
import { PDFViewer, buildMarkedPdf } from '@hbox/rn-pdf-viewer'
import type { PdfAnnotation } from '@hbox/rn-pdf-viewer'
import { DocxViewer } from '@hbox/rn-docx-viewer'
import { tamaguiConfig } from './tamagui.config'

type DocumentKind = 'pdf' | 'docx'

interface OpenedDocument {
  kind: DocumentKind
  name: string
  path: string
  /** Raw bytes, used when the picked file could not be copied to disk. */
  base64: string | null
  size: number | null
}

function formatSize(bytes: number | null): string {
  if (!bytes || bytes <= 0) return 'unknown size'
  const megabytes = bytes / (1024 * 1024)
  return megabytes >= 1 ? `${megabytes.toFixed(2)} MB` : `${Math.round(bytes / 1024)} KB`
}

/**
 * Writes a copy of the PDF with the editor's marks baked in, so the recipient
 * opens the same thing the sender saw. Returns null when there is nothing to
 * export or the copy could not be written.
 */
async function writeMarkedCopy(
  path: string,
  name: string,
  annotations: PdfAnnotation[],
): Promise<string | null> {
  if (annotations.length === 0) return null
  try {
    const source = new File(path)
    const bytes = await source.bytes()
    const marked = await buildMarkedPdf(bytes, annotations, { flatten: false })
    const target = new File(Paths.cache, `${name.replace(/\.pdf$/i, '')}-marked.pdf`)
    if (target.exists) target.delete()
    target.create()
    target.write(marked)
    return target.uri
  } catch (cause) {
    console.warn('Could not write the marked-up copy', cause)
    return null
  }
}

function kindOf(name: string): DocumentKind {
  return name.toLowerCase().endsWith('.docx') ? 'docx' : 'pdf'
}

/**
 * A fatal JS error closes a release build, so the viewers are wrapped: any
 * render failure shows the message instead of taking the app down.
 */
class ViewerErrorBoundary extends Component<
  { children: ReactNode },
  { message: string | null }
> {
  state: { message: string | null } = { message: null }

  static getDerivedStateFromError(error: Error) {
    return { message: error.message || 'Unknown rendering error' }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.warn('Document viewer failed to render', error, info.componentStack)
  }

  render() {
    if (this.state.message) {
      return (
        <View style={styles.empty}>
          <Text style={styles.emptyTitle}>This document could not be displayed</Text>
          <Text style={styles.emptyBody}>{this.state.message}</Text>
        </View>
      )
    }
    return this.props.children
  }
}

function DocumentScreen() {
  const [document, setDocument] = useState<OpenedDocument | null>(null)
  const [selectedText, setSelectedText] = useState('')
  // The native editor emits the full edit list after every change; the share
  // path needs it to write the marked-up copy.
  const annotationsRef = useRef<PdfAnnotation[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const pickDocument = useCallback(async () => {
    setError(null)
    setBusy(true)
    try {
      const result = await DocumentPicker.getDocumentAsync({
        type: [
          'application/pdf',
          'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        ],
        copyToCacheDirectory: true,
        multiple: false,
      })
      if (result.canceled) return

      const asset = result.assets[0]
      if (!asset) return

      // Copy into the app cache so the native engine always receives a stable
      // file:// path it can hand to PDFKit / PDFium / the DOCX parser. Some
      // providers hand over a content:// URI that copies to a 0-byte file; the
      // native readers then see "the file is empty", so verify the result and
      // fall back to the raw bytes for the DOCX engine.
      const kind = kindOf(asset.name)
      const source = new File(asset.uri)
      // Keep the original name in the cache copy: the share sheet shows it.
      const safeName = asset.name.replace(/[^A-Za-z0-9._-]+/g, '_') || `document.${kind}`
      const target = new File(Paths.cache, `${Date.now()}-${safeName}`)
      if (target.exists) target.delete()
      try {
        source.copy(target)
      } catch (cause) {
        console.warn('Could not cache the picked document', cause)
      }

      const copiedBytes = target.exists ? target.size ?? 0 : 0
      let base64: string | null = null
      if (copiedBytes <= 0 && kind === 'docx') {
        base64 = await source.base64()
      }

      setSelectedText('')
      setDocument({
        kind,
        name: asset.name,
        path: target.uri,
        base64,
        size: copiedBytes > 0 ? copiedBytes : asset.size ?? null,
      })
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not open that file')
    } finally {
      setBusy(false)
    }
  }, [])

  // The bottom edge matters: the viewer tool strip must stay above the system
  // navigation bar, otherwise its taps land on Home/Recents.
  // Android's JS Share module only sends text, so the file is handed to the
  // native DocumentShare module (FileProvider content URI) instead.
  const shareDocument = useCallback(async () => {
    if (!document) return
    const mimeType =
      document.kind === 'docx'
        ? 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
        : 'application/pdf'
    const bridge = NativeModules.DocumentShare as
      | { shareFile: (path: string, mimeType: string, title: string) => Promise<boolean> }
      | undefined
    if (bridge) {
      try {
        // Annotated markup only lives in the editor, so the shared file is a
        // copy with the marks written into it.
        const marked =
          document.kind === 'pdf'
            ? await writeMarkedCopy(document.path, document.name, annotationsRef.current)
            : null
        await bridge.shareFile(marked ?? document.path, mimeType, document.name)
        return
      } catch (cause) {
        console.warn('Could not share the document file', cause)
      }
    }
    await Share.share({ message: document.name })
  }, [document])

  return (
    <SafeAreaView style={styles.root} edges={['top', 'left', 'right', 'bottom']}>
      <StatusBar style="dark" />

      {/* The PDF chrome carries its own app bar (back, tools), so the screen
          header is only shown for the empty state and for DOCX documents. */}
      {document?.kind === 'pdf' ? null : (
      <View style={styles.header}>
        <View style={styles.headerText}>
          <Text style={styles.title}>Document Viewer Kit</Text>
          <Text style={styles.subtitle} numberOfLines={1}>
            {document
              ? `${document.name} · ${formatSize(document.size)}`
              : 'No document open (PDF or DOCX)'}
          </Text>
        </View>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Open a PDF or DOCX from this device"
          style={({ pressed }) => [styles.button, pressed && styles.buttonPressed]}
          onPress={pickDocument}
          disabled={busy}
        >
          {busy ? (
            <ActivityIndicator color="#ffffff" size="small" />
          ) : (
            <Text style={styles.buttonText}>Open</Text>
          )}
        </Pressable>
      </View>
      )}

      {error ? <Text style={styles.error}>{error}</Text> : null}
      {selectedText ? (
        <Text style={styles.selection} numberOfLines={2}>
          Selected: {selectedText}
        </Text>
      ) : null}

      <View style={styles.viewer}>
        <ViewerErrorBoundary key={document?.path ?? 'empty'}>
          {document?.kind === 'pdf' ? (
            <PDFViewer
              src={document.path}
              onBack={() => setDocument(null)}
              onShare={shareDocument}
              onAnnotationsChange={(next) => {
                annotationsRef.current = next
              }}
              onTextSelected={setSelectedText}
            />
        ) : document?.kind === 'docx' ? (
          <DocxViewer
            src={document.base64 ? { base64: document.base64 } : { uri: document.path }}
            fileName={document.name}
            defaultZoom="fit-width"
            onRequestUpload={pickDocument}
            onError={(failure) => setError(`${failure.code}: ${failure.message}`)}
          />
        ) : (
          <View style={styles.empty}>
            <Text style={styles.emptyTitle}>Pick a PDF or DOCX to get started</Text>
            <Text style={styles.emptyBody}>
              PDFs render through PDFKit / PDFium. DOCX files are parsed and paginated
              natively, then painted page by page.
            </Text>
          </View>
          )}
        </ViewerErrorBoundary>
      </View>
    </SafeAreaView>
  )
}

export default function App() {
  // The Tamagui skins expect the consumer to own the config and provider.
  return (
    <TamaguiProvider config={tamaguiConfig} defaultTheme="light">
      <SafeAreaProvider>
        <DocumentScreen />
      </SafeAreaProvider>
    </TamaguiProvider>
  )
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#f4f5f7',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingHorizontal: 16,
    paddingTop: 12,
    paddingBottom: 12,
  },
  headerText: {
    flex: 1,
  },
  title: {
    fontSize: 20,
    fontWeight: '700',
    color: '#111827',
  },
  subtitle: {
    marginTop: 2,
    fontSize: 13,
    color: '#6b7280',
  },
  button: {
    minWidth: 88,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 16,
    paddingVertical: 10,
    borderRadius: 999,
    backgroundColor: '#1f6feb',
  },
  buttonPressed: {
    opacity: 0.8,
  },
  buttonText: {
    color: '#ffffff',
    fontWeight: '600',
    fontSize: 14,
  },
  error: {
    marginHorizontal: 16,
    marginBottom: 8,
    color: '#b00020',
    fontSize: 13,
  },
  selection: {
    marginHorizontal: 16,
    marginBottom: 8,
    color: '#374151',
    fontSize: 13,
  },
  viewer: {
    flex: 1,
    overflow: 'hidden',
    backgroundColor: '#ffffff',
  },
  empty: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 32,
    gap: 8,
  },
  emptyTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: '#111827',
  },
  emptyBody: {
    fontSize: 13,
    lineHeight: 19,
    textAlign: 'center',
    color: '#6b7280',
  },
})
