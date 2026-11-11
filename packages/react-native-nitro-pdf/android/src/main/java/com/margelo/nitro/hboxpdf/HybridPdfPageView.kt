package com.margelo.nitro.hboxpdf

import com.facebook.react.uimanager.ThemedReactContext

class HybridPdfPageView(reactContext: ThemedReactContext) : HybridPdfPageViewSpec() {
  private val pageView = PdfPageAndroidView(reactContext)

  override val view: PdfPageAndroidView
    get() = pageView

  override var documentId: String = ""
    set(value) {
      field = value
      pageView.documentId = value
      pageView.invalidate()
    }

  override var page: Double = 0.0
    set(value) {
      field = value
      pageView.pageIndex = value.toInt()
      pageView.invalidate()
    }

  override var scale: Double = 1.0
    set(value) {
      field = value
      pageView.invalidate()
    }

  override var rotation: Double = 0.0
    set(value) {
      field = value
      pageView.rotationDegrees = value.toInt()
      pageView.invalidate()
    }

  override var highlights: DoubleArray = doubleArrayOf()
    set(value) {
      field = value
      pageView.highlights = value
      pageView.invalidate()
    }

  override var onTextSelected: TextSelectionCallback? = null

  override fun onDropView() {
    pageView.documentId = ""
  }
}
