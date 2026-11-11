package com.margelo.nitro.hboxdocx

import com.facebook.react.uimanager.ThemedReactContext

class HybridDocxThumbnail(reactContext: ThemedReactContext) : HybridDocxThumbnailSpec() {
  private val thumbnailView = DocxThumbnailView(reactContext)

  override val view: DocxThumbnailView
    get() = thumbnailView

  override var documentId: String = ""
    set(value) {
      field = value
      thumbnailView.documentId = value
      thumbnailView.invalidate()
    }

  override var page: Double = 0.0
    set(value) {
      field = value
      thumbnailView.pageIndex = value.toInt()
      thumbnailView.invalidate()
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
      thumbnailView.palette = value
      thumbnailView.invalidate()
    }

  override var scale: Double = 0.2
    set(value) {
      field = value
      thumbnailView.pageScale = value.toFloat()
      thumbnailView.invalidate()
    }

  override fun afterUpdate() {
    thumbnailView.invalidate()
  }

  override fun onDropView() {
    thumbnailView.documentId = ""
  }
}
