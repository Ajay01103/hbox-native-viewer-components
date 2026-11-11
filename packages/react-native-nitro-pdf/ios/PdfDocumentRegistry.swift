import Foundation
import PDFKit

/// Holds every open `PDFDocument` keyed by the id exposed to JS.
///
/// `PdfPageView` only receives a `documentId` prop, so the page views look the
/// document up here instead of passing native objects through JS.
final class PdfDocumentRegistry {
  static let shared = PdfDocumentRegistry()

  private var documents: [String: PDFDocument] = [:]
  private let lock = NSLock()

  private init() {}

  func store(_ document: PDFDocument, id: String) {
    lock.lock()
    defer { lock.unlock() }
    documents[id] = document
  }

  func document(id: String) -> PDFDocument? {
    lock.lock()
    defer { lock.unlock() }
    return documents[id]
  }

  func remove(id: String) {
    lock.lock()
    defer { lock.unlock() }
    documents.removeValue(forKey: id)
  }
}
