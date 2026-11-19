package com.margelo.nitro.hboxdocx

import android.view.View
import com.facebook.react.uimanager.ThemedReactContext

/**
 * Nitro host for the fully native DOCX viewer.
 *
 * The chrome is wired once in `init` and reads the replaceable callbacks, so a
 * React re-render that swaps a handler never leaves a stale reference behind.
 */
class HybridDocxEditorView(reactContext: ThemedReactContext) : HybridDocxEditorViewSpec() {
  private val chrome = DocxChromeView(reactContext)

  override val view: View
    get() = chrome

  init {
    chrome.onPageChange = { page, total ->
      onPageChange.f.invoke(page.toDouble(), total.toDouble())
    }
    chrome.onZoomSettled = { scale -> onZoomSettled.f.invoke(scale.toDouble()) }
    chrome.onZoomOut = { onZoomOut.f.invoke() }
    chrome.onZoomIn = { onZoomIn.f.invoke() }
    chrome.onZoomReset = { onZoomReset.f.invoke() }
    chrome.onPrevious = { onPrevious.f.invoke() }
    chrome.onNext = { onNext.f.invoke() }
    chrome.onToggleTheme = { onToggleTheme.f.invoke() }
    chrome.onUpload = { onUpload.f.invoke() }
    chrome.onDownload = { onDownload.f.invoke() }
    chrome.onCycleChanges = { onCycleChanges.f.invoke() }
    chrome.onViewport = { width, height ->
      onViewport.f.invoke(width.toDouble(), height.toDouble())
    }
  }

  override var documentId: String = ""
    set(value) {
      field = value
      chrome.setDocumentId(value)
    }

  override var scale: Double = 1.0
    set(value) {
      field = value
      chrome.setScale(value.toFloat())
    }

  override var scrollToPage: Double = 1.0
    set(value) {
      field = value
      chrome.setJump(value.toInt(), scrollNonce.toInt())
    }

  override var scrollNonce: Double = 0.0
    set(value) {
      field = value
      chrome.setJump(scrollToPage.toInt(), value.toInt())
    }

  override var palette: DocxPalette = DEFAULT_PALETTE
    set(value) {
      field = value
      chrome.setPalette(value)
    }

  override var trackedChanges: String = "markup"
    set(value) {
      field = value
      chrome.setTrackedChanges(value)
    }

  override var fileName: String = ""
    set(value) {
      field = value
      chrome.setFileName(value)
    }

  override var page: Double = 1.0
    set(value) {
      field = value
      chrome.setPage(value.toInt(), pageCount.toInt())
    }

  override var pageCount: Double = 1.0
    set(value) {
      field = value
      chrome.setPage(page.toInt(), value.toInt())
    }

  override var zoomLabel: String = "Fit width"
    set(value) {
      field = value
      chrome.setZoomLabel(value)
    }

  override var isDark: Boolean = false
    set(value) {
      field = value
      chrome.setDarkMode(value)
    }

  override var showUpload: Boolean = true
    set(value) {
      field = value
      chrome.setVisibilityFlags(value, showDownload)
    }

  override var showDownload: Boolean = true
    set(value) {
      field = value
      chrome.setVisibilityFlags(showUpload, value)
    }

  override var showThumbnails: Boolean = true
    set(value) {
      field = value
      chrome.setTabs(value, showOutline, showComments, showChanges)
    }

  override var showOutline: Boolean = true
    set(value) {
      field = value
      chrome.setTabs(showThumbnails, value, showComments, showChanges)
    }

  override var showComments: Boolean = true
    set(value) {
      field = value
      chrome.setTabs(showThumbnails, showOutline, value, showChanges)
    }

  override var showChanges: Boolean = false
    set(value) {
      field = value
      chrome.setTabs(showThumbnails, showOutline, showComments, value)
    }

  override var outlineJson: String = "[]"
    set(value) {
      field = value
      chrome.setOutline(value)
    }

  override var commentsJson: String = "[]"
    set(value) {
      field = value
      chrome.setComments(value)
    }

  override var onPageChange: ViewerPageCallback = ViewerPageCallback { _, _ -> }
  override var onZoomSettled: ViewerZoomCallback = ViewerZoomCallback { _ -> }
  override var onViewport: ViewerSizeCallback = ViewerSizeCallback { _, _ -> }
  override var onZoomOut: ViewerVoidCallback = ViewerVoidCallback {}
  override var onZoomIn: ViewerVoidCallback = ViewerVoidCallback {}
  override var onZoomReset: ViewerVoidCallback = ViewerVoidCallback {}
  override var onPrevious: ViewerVoidCallback = ViewerVoidCallback {}
  override var onNext: ViewerVoidCallback = ViewerVoidCallback {}
  override var onToggleTheme: ViewerVoidCallback = ViewerVoidCallback {}
  override var onUpload: ViewerVoidCallback = ViewerVoidCallback {}
  override var onDownload: ViewerVoidCallback = ViewerVoidCallback {}
  override var onCycleChanges: ViewerVoidCallback = ViewerVoidCallback {}

  override fun onDropView() {
    chrome.release()
  }

  private companion object {
    val DEFAULT_PALETTE = DocxPalette(
      appBg = "#f4f4f5",
      pageBg = "#ffffff",
      pageBorder = "#e4e4e7",
      text = "#18181b",
      muted = "#71717a",
      selection = "rgba(59,130,246,0.30)",
      commentHighlight = "rgba(250,204,21,0.35)",
      insertColor = "#16a34a",
      deleteColor = "#dc2626",
      pageDarkMode = false,
    )
  }
}
