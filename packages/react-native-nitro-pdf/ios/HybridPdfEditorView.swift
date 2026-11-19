import NitroModules
import UIKit

/// Host for the native PDF editor surface.
///
/// The editor chrome is painted with Android views; on iOS `PDFViewer` keeps
/// using the React implementation, so this host only exists to keep the hybrid
/// view registered and the pod compiling.
final class HybridPdfEditorView: HybridPdfEditorViewSpec_base, HybridPdfEditorViewSpec_protocol {
  let view = UIView()

  var documentPath: String = ""
  var annotations: String = ""
  var initialPage: Double = 1
  var onBack: PdfVoidCallback = .init(f: {})
  var onShare: PdfVoidCallback = .init(f: {})
  var onReadAloud: PdfNumberCallback = .init(f: { _ in })
  var onAnnotationsChange: PdfStringCallback = .init(f: { _ in })
  var onPageChange: PdfNumberCallback = .init(f: { _ in })
  var backToken: Double = 0

  init() {
    view.backgroundColor = .systemBackground
  }

  func onDropView() {}
}
