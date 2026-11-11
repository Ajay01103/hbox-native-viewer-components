package com.margelo.nitro.hboxpdf

import io.legere.pdfiumandroid.PdfDocument
import java.util.concurrent.ConcurrentHashMap

/** Holds every open PDFium document keyed by the id exposed to JS. */
internal object PdfDocumentRegistry {
  private val documents = ConcurrentHashMap<String, PdfDocument>()

  fun store(id: String, document: PdfDocument) {
    documents[id] = document
  }

  fun get(id: String): PdfDocument? {
    return documents[id]
  }

  fun remove(id: String) {
    documents.remove(id)
  }
}
