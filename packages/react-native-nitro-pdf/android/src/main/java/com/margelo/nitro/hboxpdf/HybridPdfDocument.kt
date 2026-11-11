package com.margelo.nitro.hboxpdf

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import com.margelo.nitro.core.Promise
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfiumCore
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class HybridPdfDocument : HybridPdfDocumentSpec() {
  private val documentId: String = UUID.randomUUID().toString()
  private val thumbnails = ConcurrentHashMap<String, String>()

  private var core: PdfiumCore? = null
  private var document: PdfDocument? = null
  private var fileDescriptor: ParcelFileDescriptor? = null

  override val id: String
    get() = documentId

  override val pageCount: Double
    get() = (document?.getPageCount() ?: 0).toDouble()

  override fun open(path: String, password: String?): Promise<Unit> = Promise.async {
    PdfiumLock.withLock {
      val localPath = if (path.startsWith("file://")) path.substring("file://".length) else path
      val file = File(localPath)
      require(file.exists()) { "Could not open PDF at $localPath" }

      // Release a previous document for the same hybrid before reopening.
      releaseUnderLock()

      val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
      val pdfium = PdfiumCore()
      val opened = try {
        pdfium.newDocument(descriptor, password)
      } catch (error: Throwable) {
        descriptor.close()
        throw error
      }
      if (opened.getPageCount() <= 0) {
        opened.close()
        descriptor.close()
        throw IllegalArgumentException("The PDF has no pages")
      }

      fileDescriptor = descriptor
      core = pdfium
      document = opened
      thumbnails.clear()
      PdfDocumentRegistry.store(documentId, opened)
    }
  }

  override fun getPageSize(page: Double): PageSize {
    return PdfiumLock.withLock {
      val opened = openPageUnderLock(page)
      try {
        PageSize(
          opened.getPageWidthPoint().toDouble(),
          opened.getPageHeightPoint().toDouble(),
        )
      } finally {
        opened.close()
      }
    }
  }

  override fun renderThumbnail(page: Double, width: Double): Promise<String> = Promise.async {
    val cacheKey = "${page.toInt()}-${width.toInt()}"
    thumbnails[cacheKey]?.let { return@async it }

    PdfiumLock.withLock {
      val opened = openPageUnderLock(page)
      try {
        val pageWidth = opened.getPageWidthPoint().toDouble().coerceAtLeast(1.0)
        val pageHeight = opened.getPageHeightPoint().toDouble()
        val targetWidth = width.toInt().coerceIn(1, 2048)
        val targetHeight = (targetWidth * (pageHeight / pageWidth)).toInt().coerceIn(1, 2048)
        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        try {
          opened.renderPageBitmap(bitmap, 0, 0, targetWidth, targetHeight, true)

          val directory = File(System.getProperty("java.io.tmpdir") ?: ".", "hbox-nitro-pdf")
          directory.mkdirs()
          val file = File(directory, "$documentId-$cacheKey.png")
          FileOutputStream(file).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
          }
          thumbnails[cacheKey] = file.absolutePath
          return@withLock file.absolutePath
        } finally {
          // The bitmap never reached a canvas, so recycling it is safe here.
          bitmap.recycle()
        }
      } finally {
        opened.close()
      }
    }
  }

  override fun search(query: String): Promise<Array<SearchHit>> {
    // TODO(roadmap step 5): PDFium text APIs (FPDFText_*) drive search on Android.
    return Promise.rejected(UnsupportedOperationException("Text search is not implemented on Android yet"))
  }

  override fun getPageText(page: Double): Promise<String> {
    // TODO(roadmap step 5): extract text through PdfTextPage.
    return Promise.rejected(UnsupportedOperationException("Text extraction is not implemented on Android yet"))
  }

  override fun close() {
    PdfiumLock.withLock {
      releaseUnderLock()
    }
  }

  /**
   * Waits for any in-flight rasterization (the page views use the same lock)
   * before freeing the document, so a page can never draw a freed handle.
   */
  private fun releaseUnderLock() {
    PdfDocumentRegistry.remove(documentId)
    document?.close()
    document = null
    try {
      fileDescriptor?.close()
    } catch (_: Throwable) {
      // Already closed; nothing to do.
    }
    fileDescriptor = null
    core = null
    thumbnails.clear()
  }

  private fun openPageUnderLock(page: Double): io.legere.pdfiumandroid.PdfPage {
    val opened = document ?: throw IllegalStateException("No PDF document is open")
    val index = page.toInt()
    require(index >= 0 && index < opened.getPageCount()) { "Page $index is out of range" }
    return opened.openPage(index) ?: throw IllegalStateException("Could not open page $index")
  }
}
