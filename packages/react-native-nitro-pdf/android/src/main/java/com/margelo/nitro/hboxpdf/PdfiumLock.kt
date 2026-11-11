package com.margelo.nitro.hboxpdf

import java.util.concurrent.locks.ReentrantLock

/**
 * PDFium is not thread safe, and it segfaults (native crash, no stack trace) if
 * a document is closed, or a page is opened, while another thread is inside
 * `FPDF_*`. Every native call that touches a [io.legere.pdfiumandroid.PdfDocument]
 * or its pages goes through this lock.
 *
 * Rendering happens on a background renderer, so callers on the UI thread and
 * on the JS thread are serialized through here.
 */
internal object PdfiumLock {
  private val lock = ReentrantLock()

  fun <T> withLock(block: () -> T): T {
    lock.lock()
    try {
      return block()
    } finally {
      lock.unlock()
    }
  }
}
