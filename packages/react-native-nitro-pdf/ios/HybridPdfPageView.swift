import Foundation
import PDFKit
import UIKit
import NitroModules

final class HybridPdfPageView: HybridPdfPageViewSpec_base, HybridPdfPageViewSpec_protocol {
  let view: PdfPageUIView

  var documentId: String = "" {
    didSet { view.documentId = documentId }
  }

  var page: Double = 0 {
    didSet { view.page = Int(page) }
  }

  var scale: Double = 1 {
    didSet { view.pageScale = scale }
  }

  var rotation: Double = 0 {
    didSet { view.rotationDegrees = Int(rotation) }
  }

  var highlights: [Double] = [] {
    didSet { view.highlights = highlights }
  }

  var onTextSelected: TextSelectionCallback?

  init() {
    view = PdfPageUIView()
  }

  func afterUpdate() {
    view.setNeedsDisplay()
  }

  func onDropView() {
    view.documentId = ""
  }
}

/// Draws a single PDF page with PDFKit. Page points are used as the drawing
/// space, so the page stays sharp at any zoom level.
final class PdfPageUIView: UIView {
  var documentId: String = "" {
    didSet { setNeedsDisplay() }
  }

  var page: Int = 0 {
    didSet { setNeedsDisplay() }
  }

  /// Zoom factor requested by JS. Drawing is resolution independent, so this
  /// only affects how highlights are inset, not the render quality.
  var pageScale: Double = 1 {
    didSet { setNeedsDisplay() }
  }

  var rotationDegrees: Int = 0 {
    didSet { setNeedsDisplay() }
  }

  var highlights: [Double] = [] {
    didSet { setNeedsDisplay() }
  }

  override init(frame: CGRect) {
    super.init(frame: frame)
    backgroundColor = .white
    isOpaque = true
    contentMode = .redraw
  }

  required init?(coder: NSCoder) {
    super.init(coder: coder)
    backgroundColor = .white
    isOpaque = true
    contentMode = .redraw
  }

  override func draw(_ rect: CGRect) {
    guard let context = UIGraphicsGetCurrentContext() else {
      return
    }
    UIColor.white.setFill()
    context.fill(rect)

    guard
      let document = PdfDocumentRegistry.shared.document(id: documentId),
      page >= 0,
      page < document.pageCount,
      let pdfPage = document.page(at: page)
    else {
      return
    }

    let bounds = pdfPage.bounds(for: .mediaBox)
    let rotated = rotationDegrees == 90 || rotationDegrees == 270
    let rotatedSize = rotated
      ? CGSize(width: bounds.height, height: bounds.width)
      : bounds.size
    guard rotatedSize.width > 0, rotatedSize.height > 0 else {
      return
    }

    let fitScale = min(rect.width / rotatedSize.width, rect.height / rotatedSize.height)

    context.saveGState()
    context.translateBy(x: rect.midX, y: rect.midY)
    context.rotate(by: CGFloat(rotationDegrees) * .pi / 180)
    context.scaleBy(x: fitScale, y: -fitScale)
    context.translateBy(x: -bounds.midX, y: -bounds.midY)

    pdfPage.draw(with: .mediaBox, to: context)

    if !highlights.isEmpty {
      context.setFillColor(UIColor.systemYellow.withAlphaComponent(0.35).cgColor)
      for index in stride(from: 0, to: highlights.count - 3, by: 4) {
        let highlight = CGRect(
          x: highlights[index],
          y: highlights[index + 1],
          width: highlights[index + 2],
          height: highlights[index + 3]
        )
        context.fill(highlight)
      }
    }

    context.restoreGState()
  }
}
