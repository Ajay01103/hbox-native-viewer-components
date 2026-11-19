package com.margelo.nitro.hboxpdf

import android.view.View
import com.facebook.react.uimanager.ThemedReactContext
import com.margelo.nitro.hboxpdf.editor.PdfEditorView

/**
 * Nitro host for the fully native PDF editor surface.
 *
 * The callbacks are wired once in `init` and read the (replaceable) props, so a
 * re-render that swaps a JS function never leaves the editor pointing at a
 * stale callback or at `null`.
 */
class HybridPdfEditorView(reactContext: ThemedReactContext) : HybridPdfEditorViewSpec() {
  private val editor = PdfEditorView(reactContext)

  override val view: View
    get() = editor

  init {
    editor.onBack = { onBack.f.invoke() }
    editor.onShare = { onShare.f.invoke() }
    editor.onReadAloud = { page -> onReadAloud.f.invoke(page.toDouble()) }
    editor.onMarksChanged = { json -> onAnnotationsChange.f.invoke(json) }
    editor.onPageChanged = { page -> onPageChange.f.invoke(page.toDouble()) }
    editor.onDocumentReady = { total -> onDocumentLoaded.f.invoke(total.toDouble()) }
  }

  override var documentPath: String = ""
    set(value) {
      field = value
      editor.setDocumentPath(value)
    }

  override var annotations: String = ""
    set(value) {
      field = value
      editor.setInitialAnnotations(value)
    }

  override var initialPage: Double = 1.0
    set(value) {
      field = value
    }

  override var onBack: PdfVoidCallback = PdfVoidCallback {}
  override var onShare: PdfVoidCallback = PdfVoidCallback {}
  override var onReadAloud: PdfNumberCallback = PdfNumberCallback {}
  override var onAnnotationsChange: PdfStringCallback = PdfStringCallback {}
  override var onPageChange: PdfNumberCallback = PdfNumberCallback {}
  override var onDocumentLoaded: PdfNumberCallback = PdfNumberCallback {}

  override var backToken: Double = 0.0
    set(value) {
      field = value
      editor.setBackToken(value.toInt())
    }

  override fun onDropView() {
    editor.release()
  }
}
