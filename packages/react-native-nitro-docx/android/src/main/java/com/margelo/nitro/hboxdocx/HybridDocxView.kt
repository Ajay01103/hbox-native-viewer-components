package com.margelo.nitro.hboxdocx

import com.facebook.react.uimanager.ThemedReactContext

class HybridDocxView(reactContext: ThemedReactContext) : HybridDocxViewSpec() {
  private val listView = DocxPageListView(reactContext)

  override val view: DocxPageListView
    get() = listView

  override var documentId: String = ""
    set(value) {
      field = value
      listView.documentId = value
    }

  override var scale: Double = 1.0
    set(value) {
      field = value
      listView.pageScale = value.toFloat()
    }

  override var scrollToPage: Double = 0.0
    set(value) {
      field = value
    }

  override var scrollNonce: Double = 0.0
    set(value) {
      field = value
      listView.scrollToPage(scrollToPage.toInt() - 1, animated = false)
    }

  override var palette: DocxPalette = DocxPalette(
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
    set(value) {
      field = value
      listView.palette = value
    }

  override var trackedChanges: String = "markup"
    set(value) {
      field = value
      listView.mode = value
    }

  override var onPageChange: PageChangeCallback? = null
  override var onLayoutProgress: LayoutProgressCallback? = null
  override var onChromeVisibility: ChromeVisibilityCallback? = null
  override var onCommentPress: CommentPressCallback? = null
  override var onSelectionChange: SelectionChangeCallback? = null
  override var onZoomSettled: ZoomSettledCallback? = null

  init {
    listView.onPageChange = { page, total ->
      onPageChange?.f?.invoke(page.toDouble(), total.toDouble())
    }
    listView.onLayoutProgress = { pages, final ->
      onLayoutProgress?.f?.invoke(pages.toDouble(), final)
    }
    listView.onChromeVisibility = { visible ->
      onChromeVisibility?.f?.invoke(visible)
    }
    listView.onTextCopied = {
      onSelectionChange?.f?.invoke(true)
    }
    listView.onZoomSettled = { zoom ->
      onZoomSettled?.f?.invoke(zoom.toDouble())
    }
  }

  override fun afterUpdate() {
    listView.refresh()
    listView.invalidate()
  }

  override fun onDropView() {
    listView.documentId = ""
  }
}
