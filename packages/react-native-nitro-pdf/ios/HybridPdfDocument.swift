import Foundation
import PDFKit
import NitroModules

final class HybridPdfDocument: HybridPdfDocumentSpec_base, HybridPdfDocumentSpec_protocol {
  private let documentId = UUID().uuidString
  private let queue = DispatchQueue(label: "com.hbox.nitropdf.document", qos: .userInitiated)
  private var document: PDFDocument?

  /// Cached thumbnail paths keyed by `page-width`.
  private var thumbnailCache: [String: String] = [:]

  var id: String {
    return documentId
  }

  var pageCount: Double {
    return Double(document?.pageCount ?? 0)
  }

  func open(path: String, password: String?) throws -> Promise<Void> {
    return Promise.parallel { [weak self] in
      guard let self else {
        throw PdfError.documentClosed
      }
      let localPath = path.hasPrefix("file://")
        ? String(path.dropFirst("file://".count))
        : path
      guard let document = PDFDocument(url: URL(fileURLWithPath: localPath)) else {
        throw PdfError.cannotOpen(localPath)
      }
      if document.isLocked {
        guard let password, document.unlock(withPassword: password) else {
          throw PdfError.wrongPassword
        }
      }
      guard document.pageCount > 0 else {
        throw PdfError.emptyDocument
      }
      self.document = document
      self.thumbnailCache.removeAll()
      PdfDocumentRegistry.shared.store(document, id: self.documentId)
    }
  }

  func getPageSize(page: Double) throws -> PageSize {
    let bounds = try pageBounds(page)
    return PageSize(width: bounds.width, height: bounds.height)
  }

  func renderThumbnail(page: Double, width: Double) throws -> Promise<String> {
    return Promise.parallel { [weak self] in
      guard let self else {
        throw PdfError.documentClosed
      }
      let cacheKey = "\(Int(page))-\(Int(width))"
      if let cached = self.thumbnailCache[cacheKey] {
        return cached
      }
      let pageSize = try self.pageBounds(page)
      let ratio = pageSize.height / pageSize.width
      let target = CGSize(width: max(width, 1), height: max(width * ratio, 1))
      let pdfPage = try self.pdfPage(page)
      let image = pdfPage.thumbnail(of: target, for: .mediaBox)
      guard let data = image.pngData() else {
        throw PdfError.renderFailed
      }
      let directory = FileManager.default.temporaryDirectory
        .appendingPathComponent("hbox-nitro-pdf", isDirectory: true)
      try FileManager.default.createDirectory(
        at: directory,
        withIntermediateDirectories: true
      )
      let file = directory.appendingPathComponent("\(self.documentId)-\(cacheKey).png")
      try data.write(to: file, options: .atomic)
      let path = file.path
      self.thumbnailCache[cacheKey] = path
      return path
    }
  }

  func search(query: String) throws -> Promise<[SearchHit]> {
    return Promise.parallel { [weak self] in
      guard let self, let document = self.document else {
        throw PdfError.documentClosed
      }
      let selections = document.findString(query, withOptions: [.caseInsensitive])
      return selections.compactMap { selection -> SearchHit? in
        guard let page = selection.pages.first else {
          return nil
        }
        let index = document.index(for: page)
        guard index != NSNotFound else {
          return nil
        }
        let bounds = selection.bounds(for: page)
        if bounds.isEmpty {
          return nil
        }
        return SearchHit(
          page: Double(index),
          rects: [
            Double(bounds.origin.x),
            Double(bounds.origin.y),
            Double(bounds.width),
            Double(bounds.height),
          ]
        )
      }
    }
  }

  func getPageText(page: Double) throws -> Promise<String> {
    return Promise.parallel { [weak self] in
      guard let self else {
        throw PdfError.documentClosed
      }
      return try self.pdfPage(page).string ?? ""
    }
  }

  func close() throws {
    document = nil
    thumbnailCache.removeAll()
    PdfDocumentRegistry.shared.remove(id: documentId)
  }

  // MARK: - Helpers

  private func pdfPage(_ page: Double) throws -> PDFPage {
    guard let document else {
      throw PdfError.documentClosed
    }
    let index = Int(page)
    guard index >= 0, index < document.pageCount, let pdfPage = document.page(at: index) else {
      throw PdfError.pageOutOfRange(index)
    }
    return pdfPage
  }

  private func pageBounds(_ page: Double) throws -> CGRect {
    return try pdfPage(page).bounds(for: .mediaBox)
  }
}

enum PdfError: Error, LocalizedError {
  case documentClosed
  case cannotOpen(String)
  case wrongPassword
  case emptyDocument
  case pageOutOfRange(Int)
  case renderFailed

  var errorDescription: String? {
    switch self {
    case .documentClosed:
      return "No PDF document is open"
    case .cannotOpen(let path):
      return "Could not open PDF at \(path)"
    case .wrongPassword:
      return "The PDF is password protected and the password is missing or wrong"
    case .emptyDocument:
      return "The PDF has no pages"
    case .pageOutOfRange(let page):
      return "Page \(page) is out of range"
    case .renderFailed:
      return "Could not render the PDF page"
    }
  }
}
