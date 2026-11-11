import Foundation
import UIKit

struct DocxRunModel {
  var text: String
  var bold = false
  var italic = false
  var underline = false
  var strike = false
  var color: UIColor?
  var sizePt: CGFloat = 0
  var revision = "" // "insert" | "delete"
  var commentIds: [String] = []
}

struct DocxParagraphModel {
  var anchorId: String
  var runs: [DocxRunModel] = []
  var headingLevel = 0
  var listLevel = -1
  var marker = ""
  var indentLeft: CGFloat = 0
  var spaceAfter: CGFloat = 4
  var pageBreakBefore = false

  var plainText: String {
    return runs.map { $0.text }.joined()
  }
}

struct DocxCommentModel {
  let id: String
  let author: String
  let date: String
  let text: String
}

struct DocxOutlineModel {
  let id: String
  let title: String
  let level: Int
}

struct DocxGeometry {
  var width: CGFloat = 612
  var height: CGFloat = 792
  var marginLeft: CGFloat = 72
  var marginRight: CGFloat = 72
  var marginTop: CGFloat = 72
  var marginBottom: CGFloat = 72

  var contentWidth: CGFloat { max(1, width - marginLeft - marginRight) }
  var contentHeight: CGFloat { max(1, height - marginTop - marginBottom) }
}

final class DocxModel {
  let fileName: String
  let title: String
  let geometry: DocxGeometry
  let paragraphs: [DocxParagraphModel]
  let comments: [DocxCommentModel]
  let outline: [DocxOutlineModel]
  let header: String
  let footer: String
  let hasTrackedChanges: Bool
  let warnings: [String]

  init(
    fileName: String,
    title: String,
    geometry: DocxGeometry,
    paragraphs: [DocxParagraphModel],
    comments: [DocxCommentModel],
    outline: [DocxOutlineModel],
    header: String,
    footer: String,
    hasTrackedChanges: Bool,
    warnings: [String]
  ) {
    self.fileName = fileName
    self.title = title
    self.geometry = geometry
    self.paragraphs = paragraphs
    self.comments = comments
    self.outline = outline
    self.header = header
    self.footer = footer
    self.hasTrackedChanges = hasTrackedChanges
    self.warnings = warnings
  }
}

enum DocxParserError: Error, LocalizedError {
  case legacyDoc
  case corrupt(String)

  var errorDescription: String? {
    switch self {
    case .legacyDoc:
      return "LEGACY_DOC: .doc is not supported; convert the file to .docx"
    case .corrupt(let detail):
      return "CORRUPT: \(detail)"
    }
  }
}

/// WordprocessingML → model. Text boxes, equations and SmartArt degrade to
/// placeholders with a warning, never a hard failure.
enum DocxParser {
  static func parse(data: Data, fileName: String) throws -> DocxModel {
    if data.count >= 4, data[0] == 0xD0, data[1] == 0xCF, data[2] == 0x11, data[3] == 0xE0 {
      throw DocxParserError.legacyDoc
    }
    let zip: DocxZip
    do {
      zip = try DocxZip(data: data)
    } catch let error as DocxZipError {
      throw DocxParserError.corrupt(error.localizedDescription)
    }
    guard let documentXML = zip.part("word/document.xml") else {
      throw DocxParserError.corrupt("this file has no word/document.xml")
    }

    let styles = DocxStyleParser.parse(zip.part("word/styles.xml"))
    let numbering = DocxNumberingParser.parse(zip.part("word/numbering.xml"))
    let delegate = DocxDocumentDelegate(styles: styles, numbering: numbering)
    let parser = XMLParser(data: documentXML)
    parser.delegate = delegate
    guard parser.parse() else {
      throw DocxParserError.corrupt("the document XML could not be parsed")
    }

    let comments = DocxCommentParser.parse(zip.part("word/comments.xml"))
    let outline = delegate.paragraphs
      .filter { $0.headingLevel > 0 && !$0.plainText.trimmingCharacters(in: .whitespaces).isEmpty }
      .map { DocxOutlineModel(id: $0.anchorId, title: String($0.plainText.prefix(200)), level: $0.headingLevel) }

    return DocxModel(
      fileName: fileName,
      title: outline.first?.title ?? (fileName as NSString).deletingPathExtension,
      geometry: delegate.geometry,
      paragraphs: delegate.paragraphs,
      comments: comments,
      outline: outline,
      header: DocxTextExtractor.firstText(zip.firstPart(prefix: "word/header")),
      footer: DocxTextExtractor.firstText(zip.firstPart(prefix: "word/footer")),
      hasTrackedChanges: delegate.paragraphs.contains { paragraph in
        paragraph.runs.contains { !$0.revision.isEmpty }
      },
      warnings: delegate.warnings
    )
  }
}

// MARK: - document.xml

final class DocxDocumentDelegate: NSObject, XMLParserDelegate {
  private(set) var paragraphs: [DocxParagraphModel] = []
  private(set) var warnings: [String] = []
  private(set) var geometry = DocxGeometry()

  private let styles: [String: DocxStyle]
  private let numbering: DocxNumbering

  private var currentParagraph: DocxParagraphModel?
  private var currentRun: DocxRunModel?
  private var paragraphStyleId = ""
  private var paragraphIndent: CGFloat = 0
  private var paragraphSpace: CGFloat = 4
  private var numberingId = ""
  private var numberingLevel = 0
  private var hasNumbering = false
  private var outlineLevel = -1
  private var revision = ""
  private var bookmark = ""
  private var pendingComments: [String] = []
  private var textBuffer = ""
  private var inTable = false
  private var tableCells: [String] = []

  init(styles: [String: DocxStyle], numbering: DocxNumbering) {
    self.styles = styles
    self.numbering = numbering
  }

  func parser(
    _ parser: XMLParser,
    didStartElement elementName: String,
    namespaceURI: String?,
    qualifiedName: String?,
    attributes: [String: String]
  ) {
    let name = Self.local(elementName)
    let attributes = Self.localAttributes(attributes)

    switch name {
    case "pgSz":
      if let width = Double(attributes["w"] ?? ""), width > 0 { geometry.width = width / 20 }
      if let height = Double(attributes["h"] ?? ""), height > 0 { geometry.height = height / 20 }
    case "pgMar":
      geometry.marginTop = Self.twips(attributes["top"], geometry.marginTop)
      geometry.marginBottom = Self.twips(attributes["bottom"], geometry.marginBottom)
      geometry.marginLeft = Self.twips(attributes["left"], geometry.marginLeft)
      geometry.marginRight = Self.twips(attributes["right"], geometry.marginRight)
    case "tbl":
      inTable = true
    case "tr":
      tableCells = []
    case "p":
      currentParagraph = DocxParagraphModel(anchorId: "p-\(paragraphs.count)")
      paragraphStyleId = ""
      paragraphIndent = 0
      paragraphSpace = 4
      numberingId = ""
      numberingLevel = 0
      hasNumbering = false
      outlineLevel = -1
      bookmark = ""
      pendingComments = []
    case "pStyle":
      paragraphStyleId = attributes["val"] ?? ""
    case "ind":
      paragraphIndent = Self.twips(attributes["left"] ?? attributes["start"], paragraphIndent)
    case "spacing":
      paragraphSpace = Self.twips(attributes["after"], paragraphSpace)
    case "numPr":
      hasNumbering = true
    case "ilvl":
      numberingLevel = Int(attributes["val"] ?? "") ?? numberingLevel
    case "numId":
      numberingId = attributes["val"] ?? ""
    case "outlineLvl":
      outlineLevel = Int(attributes["val"] ?? "") ?? outlineLevel
    case "r":
      currentRun = DocxRunModel(text: "")
    case "b":
      currentRun?.bold = Self.isOn(attributes["val"])
    case "i":
      currentRun?.italic = Self.isOn(attributes["val"])
    case "u":
      currentRun?.underline = (attributes["val"] ?? "single") != "none"
    case "strike", "dstrike":
      currentRun?.strike = Self.isOn(attributes["val"])
    case "color":
      if let value = attributes["val"], value != "auto" {
        currentRun?.color = UIColor(hex: value)
      }
    case "sz":
      currentRun?.sizePt = CGFloat(Double(attributes["val"] ?? "") ?? 0) / 2
    case "t", "delText", "instrText":
      textBuffer = ""
    case "tab":
      currentRun?.text.append("\t")
    case "br", "cr":
      currentRun?.text.append("\n")
    case "ins":
      revision = "insert"
    case "del":
      revision = "delete"
    case "commentRangeStart":
      if let id = attributes["id"] { pendingComments.append(id) }
    case "bookmarkStart":
      bookmark = attributes["name"] ?? ""
    case "drawing", "pict", "object":
      warnings.append("Images and floating objects are rendered as text placeholders")
      currentRun?.text.append("[image]")
    default:
      break
    }
  }

  func parser(_ parser: XMLParser, foundCharacters string: String) {
    textBuffer.append(string)
  }

  func parser(
    _ parser: XMLParser,
    didEndElement elementName: String,
    namespaceURI: String?,
    qualifiedName: String?
  ) {
    let name = Self.local(elementName)
    switch name {
    case "t", "delText", "instrText":
      currentRun?.text.append(textBuffer)
      textBuffer = ""
    case "r":
      if var run = currentRun, !run.text.isEmpty {
        run.revision = revision
        run.commentIds = pendingComments
        currentParagraph?.runs.append(run)
      }
      currentRun = nil
    case "ins", "del":
      revision = ""
    case "p":
      guard var paragraph = currentParagraph else { return }
      paragraph.anchorId = bookmark.isEmpty ? paragraph.anchorId : bookmark
      paragraph.headingLevel = Self.headingLevel(styleId: paragraphStyleId, outline: outlineLevel, styles: styles)
      paragraph.indentLeft = hasNumbering ? paragraphIndent + CGFloat(numberingLevel) * 18 : paragraphIndent
      paragraph.spaceAfter = paragraphSpace
      if hasNumbering {
        paragraph.listLevel = numberingLevel
        paragraph.marker = numbering.marker(numId: numberingId, level: numberingLevel)
      }
      currentParagraph = nil
      if inTable {
        tableCells.append(paragraph.plainText.trimmingCharacters(in: .whitespaces))
      } else if !paragraph.runs.isEmpty {
        paragraphs.append(paragraph)
      }
    case "tr":
      if tableCells.contains(where: { !$0.isEmpty }) {
        paragraphs.append(
          DocxParagraphModel(
            anchorId: "tbl-\(paragraphs.count)",
            runs: [DocxRunModel(text: tableCells.joined(separator: "   |   "))],
            spaceAfter: 2
          )
        )
      }
      tableCells = []
    case "tbl":
      inTable = false
    default:
      break
    }
  }

  private static func headingLevel(styleId: String, outline: Int, styles: [String: DocxStyle]) -> Int {
    if !styleId.isEmpty, let style = styles[styleId] {
      let name = style.name.lowercased()
      if name.hasPrefix("heading") {
        let digits = name.filter { $0.isNumber }
        return Int(digits.isEmpty ? "1" : digits) ?? 1
      }
      if name == "title" { return 1 }
      if style.outlineLevel >= 0 { return min(6, style.outlineLevel + 1) }
    }
    if outline >= 0 { return min(6, outline + 1) }
    return 0
  }

  private static func isOn(_ value: String?) -> Bool {
    guard let value else { return true }
    return value == "1" || value == "true" || value == "on"
  }

  private static func twips(_ value: String?, _ fallback: CGFloat) -> CGFloat {
    guard let value, let parsed = Double(value) else { return fallback }
    return CGFloat(parsed) / 20
  }

  private static func local(_ name: String) -> String {
    return name.contains(":") ? String(name.split(separator: ":").last ?? "") : name
  }

  private static func localAttributes(_ attributes: [String: String]) -> [String: String] {
    var result: [String: String] = [:]
    for (key, value) in attributes {
      result[local(key)] = value
    }
    return result
  }
}

// MARK: - styles / numbering / comments

struct DocxStyle {
  let name: String
  let outlineLevel: Int
}

enum DocxStyleParser {
  static func parse(_ data: Data?) -> [String: DocxStyle] {
    guard let data else { return [:] }
    let delegate = StyleDelegate()
    let parser = XMLParser(data: data)
    parser.delegate = delegate
    parser.parse()
    return delegate.styles
  }

  private final class StyleDelegate: NSObject, XMLParserDelegate {
    var styles: [String: DocxStyle] = [:]
    private var currentId = ""
    private var name = ""
    private var outline = -1

    func parser(
      _ parser: XMLParser,
      didStartElement elementName: String,
      namespaceURI: String?,
      qualifiedName: String?,
      attributes: [String: String]
    ) {
      let local = elementName.contains(":") ? String(elementName.split(separator: ":").last ?? "") : elementName
      let values = Dictionary(uniqueKeysWithValues: attributes.map { key, value in
        (key.contains(":") ? String(key.split(separator: ":").last ?? "") : key, value)
      })
      switch local {
      case "style":
        currentId = values["styleId"] ?? ""
        name = ""
        outline = -1
      case "name":
        name = values["val"] ?? name
      case "outlineLvl":
        outline = Int(values["val"] ?? "") ?? outline
      default:
        break
      }
    }

    func parser(
      _ parser: XMLParser,
      didEndElement elementName: String,
      namespaceURI: String?,
      qualifiedName: String?
    ) {
      let local = elementName.contains(":") ? String(elementName.split(separator: ":").last ?? "") : elementName
      if local == "style", !currentId.isEmpty {
        styles[currentId] = DocxStyle(name: name, outlineLevel: outline)
      }
    }
  }
}

final class DocxNumbering {
  private let levels: [String: [Int: (format: String, text: String, start: Int)]]
  private let mapping: [String: String]
  private var counters: [String: [Int: Int]] = [:]

  init(
    levels: [String: [Int: (format: String, text: String, start: Int)]],
    mapping: [String: String]
  ) {
    self.levels = levels
    self.mapping = mapping
  }

  func marker(numId: String, level: Int) -> String {
    guard let abstractId = mapping[numId], let definitions = levels[abstractId],
          let definition = definitions[level] else {
      return "•"
    }
    if definition.format == "bullet" || definition.format.isEmpty {
      return "•"
    }
    var counter = counters[numId] ?? [:]
    let next = (counter[level] ?? (definition.start - 1)) + 1
    counter[level] = next
    counters[numId] = counter
    var text = definition.text
    for index in 0 ..< 9 {
      let token = "%\(index + 1)"
      if text.contains(token) {
        text = text.replacingOccurrences(of: token, with: String(counter[index] ?? 1))
      }
    }
    return text
  }
}

enum DocxNumberingParser {
  static func parse(_ data: Data?) -> DocxNumbering {
    guard let data else { return DocxNumbering(levels: [:], mapping: [:]) }
    let delegate = NumberingDelegate()
    let parser = XMLParser(data: data)
    parser.delegate = delegate
    parser.parse()
    return DocxNumbering(levels: delegate.levels, mapping: delegate.mapping)
  }

  private final class NumberingDelegate: NSObject, XMLParserDelegate {
    var levels: [String: [Int: (format: String, text: String, start: Int)]] = [:]
    var mapping: [String: String] = [:]
    private var abstractId = ""
    private var numId = ""
    private var level = 0
    private var format = ""
    private var levelText = ""
    private var start = 1

    func parser(
      _ parser: XMLParser,
      didStartElement elementName: String,
      namespaceURI: String?,
      qualifiedName: String?,
      attributes: [String: String]
    ) {
      let local = elementName.contains(":") ? String(elementName.split(separator: ":").last ?? "") : elementName
      let values = Dictionary(uniqueKeysWithValues: attributes.map { key, value in
        (key.contains(":") ? String(key.split(separator: ":").last ?? "") : key, value)
      })
      switch local {
      case "abstractNum":
        abstractId = values["abstractNumId"] ?? ""
      case "num":
        numId = values["numId"] ?? ""
      case "abstractNumId":
        if !numId.isEmpty { mapping[numId] = values["val"] ?? "" }
      case "lvl":
        level = Int(values["ilvl"] ?? "") ?? 0
        format = ""
        levelText = ""
        start = 1
      case "numFmt":
        format = values["val"] ?? format
      case "lvlText":
        levelText = values["val"] ?? levelText
      case "start":
        start = Int(values["val"] ?? "") ?? start
      default:
        break
      }
    }

    func parser(
      _ parser: XMLParser,
      didEndElement elementName: String,
      namespaceURI: String?,
      qualifiedName: String?
    ) {
      let local = elementName.contains(":") ? String(elementName.split(separator: ":").last ?? "") : elementName
      if local == "lvl", !abstractId.isEmpty {
        levels[abstractId, default: [:]][level] = (format, levelText, start)
      }
    }
  }
}

enum DocxCommentParser {
  static func parse(_ data: Data?) -> [DocxCommentModel] {
    guard let data else { return [] }
    let delegate = CommentDelegate()
    let parser = XMLParser(data: data)
    parser.delegate = delegate
    parser.parse()
    return delegate.comments
  }

  private final class CommentDelegate: NSObject, XMLParserDelegate {
    var comments: [DocxCommentModel] = []
    private var id = ""
    private var author = ""
    private var date = ""
    private var text = ""

    func parser(
      _ parser: XMLParser,
      didStartElement elementName: String,
      namespaceURI: String?,
      qualifiedName: String?,
      attributes: [String: String]
    ) {
      let local = elementName.contains(":") ? String(elementName.split(separator: ":").last ?? "") : elementName
      let values = Dictionary(uniqueKeysWithValues: attributes.map { key, value in
        (key.contains(":") ? String(key.split(separator: ":").last ?? "") : key, value)
      })
      if local == "comment" {
        id = values["id"] ?? ""
        author = values["author"] ?? ""
        date = values["date"] ?? ""
        text = ""
      }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
      if !id.isEmpty { text.append(string) }
    }

    func parser(
      _ parser: XMLParser,
      didEndElement elementName: String,
      namespaceURI: String?,
      qualifiedName: String?
    ) {
      let local = elementName.contains(":") ? String(elementName.split(separator: ":").last ?? "") : elementName
      if local == "comment", !id.isEmpty {
        comments.append(
          DocxCommentModel(
            id: id,
            author: author,
            date: date,
            text: text.trimmingCharacters(in: .whitespacesAndNewlines)
          )
        )
        id = ""
      }
    }
  }
}

enum DocxTextExtractor {
  static func firstText(_ data: Data?) -> String {
    guard let data else { return "" }
    let delegate = TextDelegate()
    let parser = XMLParser(data: data)
    parser.delegate = delegate
    parser.parse()
    return String(delegate.text.prefix(160))
  }

  private final class TextDelegate: NSObject, XMLParserDelegate {
    var text = ""
    private var collecting = false

    func parser(
      _ parser: XMLParser,
      didStartElement elementName: String,
      namespaceURI: String?,
      qualifiedName: String?,
      attributes: [String: String]
    ) {
      let local = elementName.contains(":") ? String(elementName.split(separator: ":").last ?? "") : elementName
      collecting = local == "t"
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
      if collecting { text.append(string) }
    }
  }
}

extension UIColor {
  convenience init?(hex: String) {
    var value = hex.trimmingCharacters(in: .whitespacesAndNewlines)
    if value.hasPrefix("#") { value.removeFirst() }
    guard value.count == 6, let number = UInt32(value, radix: 16) else { return nil }
    self.init(
      red: CGFloat((number >> 16) & 0xFF) / 255,
      green: CGFloat((number >> 8) & 0xFF) / 255,
      blue: CGFloat(number & 0xFF) / 255,
      alpha: 1
    )
  }
}
