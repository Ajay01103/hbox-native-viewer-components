package com.margelo.nitro.hboxdocx

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

internal object DocxLimits {
  const val MAX_ENTRIES = 4096
  const val MAX_TOTAL_BYTES = 96L * 1024 * 1024
  const val MAX_PART_BYTES = 48L * 1024 * 1024
}

/** Errors are prefixed with a stable code (`CORRUPT`, `LEGACY_DOC`, ...) for JS. */
internal class DocxParseException(message: String) : IllegalStateException(message)

internal class StyleInfo(val name: String, val outlineLevel: Int)

internal class NumberingLevel(val format: String, val text: String, val start: Int)

internal class NumberingTable(
  private val abstractLevels: Map<String, Map<Int, NumberingLevel>>,
  private val numbering: Map<String, String>,
) {
  private val counters = HashMap<String, HashMap<Int, Int>>()

  /** Bullet glyph or formatted number (`%1.`) for the next list item. */
  fun marker(numId: String, level: Int): String {
    val abstractId = numbering[numId] ?: return "•"
    val levels = abstractLevels[abstractId] ?: return "•"
    val definition = levels[level] ?: return "•"
    if (definition.format == "bullet" || definition.format.isEmpty()) return "•"

    val counter = counters.getOrPut(numId) { HashMap() }
    val value = (counter[level] ?: (definition.start - 1)) + 1
    counter[level] = value
    counter.keys.filter { it > level }.forEach { counter.remove(it) }

    return definition.text.replace(Regex("%(\\d)")) { match ->
      val index = match.groupValues[1].toIntOrNull() ?: 1
      (counter[index - 1] ?: 1).toString()
    }
  }
}

private class ParagraphProps {
  var styleId: String = ""
  var alignment: String = "left"
  var indentLeft: Float = 0f
  var spaceAfter: Float = 4f
  var spaceBefore: Float = 0f
  var pageBreakBefore: Boolean = false
  var numId: String = ""
  var level: Int = 0
  var hasList: Boolean = false
  var outlineLevel: Int = -1
}

private class BodyState {
  val paragraphs = ArrayList<DocxParagraph>()
  val warnings = ArrayList<String>()
  var width: Float = 612f
  var height: Float = 792f
  var marginLeft: Float = 72f
  var marginRight: Float = 72f
  var marginTop: Float = 72f
  var marginBottom: Float = 72f
}

internal class DocxParser {
  fun parseFile(file: File, fileName: String): DocxModel = parse(file.readBytes(), fileName)

  fun parse(bytes: ByteArray, fileName: String): DocxModel {
    if (looksLikeLegacyDoc(bytes)) {
      throw DocxParseException("LEGACY_DOC: .doc is not supported; convert the file to .docx")
    }
    val entries = readEntries(bytes)
    val documentXml = entries["word/document.xml"]
      ?: throw DocxParseException("CORRUPT: this file has no word/document.xml")

    val styles = parseStyles(entries["word/styles.xml"])
    val numbering = parseNumbering(entries["word/numbering.xml"])
    val comments = parseComments(entries["word/comments.xml"])

    val body = BodyState()
    parseDocumentXml(documentXml, styles, numbering, body)

    val header = headerFooterText(entries, "header")
    val footer = headerFooterText(entries, "footer")

    val outline = body.paragraphs
      .filter { it.headingLevel > 0 && it.plainText.isNotBlank() }
      .map {
        DocxOutlineEntry(
          id = it.anchorId,
          title = it.plainText.trim().take(200),
          level = it.headingLevel,
        )
      }

    val hasTrackedChanges = body.paragraphs.any { paragraph ->
      paragraph.runs.any { it.revision.isNotEmpty() }
    }

    return DocxModel(
      fileName = fileName,
      title = outline.firstOrNull()?.title ?: fileName.substringBeforeLast('.'),
      geometry = DocxPageGeometry(
        width = body.width,
        height = body.height,
        marginLeft = body.marginLeft,
        marginRight = body.marginRight,
        marginTop = body.marginTop,
        marginBottom = body.marginBottom,
      ),
      paragraphs = body.paragraphs,
      comments = comments,
      outline = outline,
      header = header,
      footer = footer,
      hasTrackedChanges = hasTrackedChanges,
      warnings = body.warnings.toList(),
    )
  }

  // MARK: - Container

  private fun looksLikeLegacyDoc(bytes: ByteArray): Boolean {
    if (bytes.size < 4) return false
    return bytes[0] == 0xD0.toByte() &&
      bytes[1] == 0xCF.toByte() &&
      bytes[2] == 0x11.toByte() &&
      bytes[3] == 0xE0.toByte()
  }

  private fun readEntries(bytes: ByteArray): Map<String, ByteArray> {
    val entries = HashMap<String, ByteArray>()
    var total = 0L
    try {
      ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        var count = 0
        while (true) {
          val entry = zip.nextEntry ?: break
          if (++count > DocxLimits.MAX_ENTRIES) {
            throw DocxParseException("TOO_LARGE: too many zip entries")
          }
          if (entry.isDirectory) continue
          val buffer = ByteArrayOutputStream()
          val chunk = ByteArray(64 * 1024)
          var partBytes = 0L
          while (true) {
            val read = zip.read(chunk)
            if (read <= 0) break
            buffer.write(chunk, 0, read)
            partBytes += read
            total += read
            if (partBytes > DocxLimits.MAX_PART_BYTES || total > DocxLimits.MAX_TOTAL_BYTES) {
              throw DocxParseException("TOO_LARGE: uncompressed document exceeds the limit")
            }
          }
          entries[entry.name] = buffer.toByteArray()
        }
      }
    } catch (error: DocxParseException) {
      throw error
    } catch (error: Exception) {
      throw DocxParseException("CORRUPT: this file is not a readable DOCX zip (${error.message})")
    }
    return entries
  }

  // MARK: - document.xml

  private fun parseDocumentXml(
    xml: ByteArray,
    styles: Map<String, StyleInfo>,
    numbering: NumberingTable,
    body: BodyState,
  ) {
    val parser = newParser(xml)
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
      if (event == XmlPullParser.START_TAG) {
        when (localName(parser.name)) {
          "sectPr" -> readSectionProperties(parser, body)
          "p" -> {
            val paragraph = readParagraph(parser, styles, numbering, body)
            if (paragraph.runs.isNotEmpty()) body.paragraphs.add(paragraph)
          }
          "tbl" -> readTable(parser, styles, numbering, body)
          "drawing", "pict", "object" -> body.warnings.add("Floating objects are not rendered")
        }
      }
      event = parser.next()
    }
  }

  private fun readSectionProperties(parser: XmlPullParser, body: BodyState) {
    var event = parser.next()
    while (!(event == XmlPullParser.END_TAG && localName(parser.name) == "sectPr")) {
      if (event == XmlPullParser.START_TAG) {
        when (localName(parser.name)) {
          "pgSz" -> {
            val width = attribute(parser, "w")?.toFloatOrNull()
            val height = attribute(parser, "h")?.toFloatOrNull()
            if (width != null && width > 0) body.width = width / 20f
            if (height != null && height > 0) body.height = height / 20f
          }
          "pgMar" -> {
            body.marginTop = twipsToPoints(attribute(parser, "top"), body.marginTop)
            body.marginBottom = twipsToPoints(attribute(parser, "bottom"), body.marginBottom)
            body.marginLeft = twipsToPoints(attribute(parser, "left"), body.marginLeft)
            body.marginRight = twipsToPoints(attribute(parser, "right"), body.marginRight)
          }
        }
      }
      event = parser.next()
    }
  }

  private fun readTable(
    parser: XmlPullParser,
    styles: Map<String, StyleInfo>,
    numbering: NumberingTable,
    body: BodyState,
  ) {
    var cells = ArrayList<String>()
    var event = parser.next()
    while (!(event == XmlPullParser.END_TAG && localName(parser.name) == "tbl")) {
      if (event == XmlPullParser.START_TAG) {
        when (localName(parser.name)) {
          "tr" -> cells = ArrayList()
          "p" -> {
            val paragraph = readParagraph(parser, styles, numbering, body)
            if (paragraph.runs.isNotEmpty()) cells.add(paragraph.plainText.trim())
          }
          "sectPr" -> readSectionProperties(parser, body)
          "drawing", "pict" -> body.warnings.add("Images inside tables are not rendered")
        }
      } else if (event == XmlPullParser.END_TAG && localName(parser.name) == "tr") {
        if (cells.any { it.isNotEmpty() }) {
          body.paragraphs.add(
            DocxParagraph(
              anchorId = "tbl-${body.paragraphs.size}",
              runs = listOf(DocxRun(text = cells.joinToString("   |   "))),
              spaceAfter = 2f,
              isTableRow = true,
            ),
          )
        }
      }
      event = parser.next()
    }
  }

  private fun readParagraph(
    parser: XmlPullParser,
    styles: Map<String, StyleInfo>,
    numbering: NumberingTable,
    body: BodyState,
  ): DocxParagraph {
    val props = ParagraphProps()
    val runs = ArrayList<DocxRun>()
    val commentIds = ArrayList<String>()
    var revision = ""
    var bookmark = ""

    var event = parser.next()
    while (!(event == XmlPullParser.END_TAG && localName(parser.name) == "p")) {
      if (event == XmlPullParser.START_TAG) {
        when (localName(parser.name)) {
          "pPr" -> readParagraphProperties(parser, props)
          "r" -> {
            val run = readRun(parser, revision, commentIds, body)
            if (run != null) runs.add(run)
          }
          "ins" -> revision = "insert"
          "del" -> revision = "delete"
          "commentRangeStart" -> attribute(parser, "id")?.let { commentIds.add(it) }
          "bookmarkStart" -> bookmark = attribute(parser, "name") ?: bookmark
          "drawing", "pict", "object" -> {
            body.warnings.add("Floating objects are rendered as text placeholders")
            runs.add(DocxRun(text = "[image]"))
          }
        }
      } else if (event == XmlPullParser.END_TAG) {
        when (localName(parser.name)) {
          "ins" -> revision = ""
          "del" -> revision = ""
        }
      }
      event = parser.next()
    }

    val anchorId = bookmark.ifEmpty { "p-${body.paragraphs.size}" }
    val headingLevel = headingLevelFor(props, styles)
    val marker = if (props.hasList) numbering.marker(props.numId, props.level) else ""

    return DocxParagraph(
      anchorId = anchorId,
      runs = runs,
      headingLevel = headingLevel,
      listLevel = if (props.hasList) props.level else -1,
      marker = marker,
      alignment = props.alignment,
      spaceBefore = props.spaceBefore,
      spaceAfter = props.spaceAfter,
      indentLeft = if (props.hasList) props.indentLeft + props.level * 18f else props.indentLeft,
      pageBreakBefore = props.pageBreakBefore,
    )
  }

  private fun readParagraphProperties(parser: XmlPullParser, props: ParagraphProps) {
    var event = parser.next()
    while (!(event == XmlPullParser.END_TAG && localName(parser.name) == "pPr")) {
      if (event == XmlPullParser.START_TAG) {
        when (localName(parser.name)) {
          "pStyle" -> props.styleId = attribute(parser, "val") ?: props.styleId
          "jc" -> props.alignment = attribute(parser, "val") ?: props.alignment
          "ind" -> {
            val left = attribute(parser, "left") ?: attribute(parser, "start")
            props.indentLeft = twipsToPoints(left, props.indentLeft)
          }
          "spacing" -> {
            props.spaceAfter = twipsToPoints(attribute(parser, "after"), props.spaceAfter)
            props.spaceBefore = twipsToPoints(attribute(parser, "before"), props.spaceBefore)
          }
          "pageBreakBefore" -> props.pageBreakBefore = true
          "outlineLvl" -> props.outlineLevel = attribute(parser, "val")?.toIntOrNull() ?: -1
          "numPr" -> {
            props.hasList = true
            readNumberingProperties(parser, props)
          }
        }
      }
      event = parser.next()
    }
  }

  private fun readNumberingProperties(parser: XmlPullParser, props: ParagraphProps) {
    var event = parser.next()
    while (!(event == XmlPullParser.END_TAG && localName(parser.name) == "numPr")) {
      if (event == XmlPullParser.START_TAG) {
        when (localName(parser.name)) {
          "ilvl" -> props.level = attribute(parser, "val")?.toIntOrNull() ?: props.level
          "numId" -> props.numId = attribute(parser, "val") ?: props.numId
        }
      }
      event = parser.next()
    }
  }

  private fun readRun(
    parser: XmlPullParser,
    revision: String,
    commentIds: List<String>,
    body: BodyState,
  ): DocxRun? {
    val text = StringBuilder()
    var bold = false
    var italic = false
    var underline = false
    var strike = false
    var color: Int? = null
    var sizePt = 0f
    var highlight: Int? = null

    var event = parser.next()
    while (!(event == XmlPullParser.END_TAG && localName(parser.name) == "r")) {
      if (event == XmlPullParser.START_TAG) {
        when (localName(parser.name)) {
          "rPr" -> {
            val props = readRunProperties(parser)
            bold = props[0] as Boolean
            italic = props[1] as Boolean
            underline = props[2] as Boolean
            strike = props[3] as Boolean
            color = props[4] as Int?
            sizePt = props[5] as Float
            highlight = props[6] as Int?
          }
          "t", "delText", "instrText" -> text.append(parser.nextText())
          "tab" -> text.append('\t')
          "br", "cr" -> text.append('\n')
          "noBreakHyphen" -> text.append('-')
          "drawing", "pict", "object" -> {
            body.warnings.add("Embedded images are not rendered in this version")
            text.append("[image]")
          }
        }
      }
      event = parser.next()
    }

    if (text.isEmpty()) return null
    return DocxRun(
      text = text.toString(),
      bold = bold,
      italic = italic,
      underline = underline,
      strike = strike,
      color = color,
      sizePt = sizePt,
      highlight = highlight,
      revision = revision,
      commentIds = commentIds,
    )
  }

  /** Returns `[bold, italic, underline, strike, color, sizePt, highlight]`. */
  private fun readRunProperties(parser: XmlPullParser): List<Any?> {
    var bold = false
    var italic = false
    var underline = false
    var strike = false
    var color: Int? = null
    var sizePt = 0f
    var highlight: Int? = null

    var event = parser.next()
    while (!(event == XmlPullParser.END_TAG && localName(parser.name) == "rPr")) {
      if (event == XmlPullParser.START_TAG) {
        when (localName(parser.name)) {
          "b" -> bold = isOn(parser)
          "i" -> italic = isOn(parser)
          "u" -> underline = (attribute(parser, "val") ?: "single") != "none"
          "strike", "dstrike" -> strike = isOn(parser)
          "color" -> {
            val value = attribute(parser, "val")
            if (value != null && value != "auto") {
              color = parseDocxColor("#$value", 0)
            }
          }
          "sz" -> sizePt = (attribute(parser, "val")?.toFloatOrNull() ?: 0f) / 2f
          "highlight" -> {
            val value = attribute(parser, "val")
            highlight = if (value == null) null else highlightColor(value)
          }
        }
      }
      event = parser.next()
    }
    return listOf(bold, italic, underline, strike, color, sizePt, highlight)
  }

  private fun isOn(parser: XmlPullParser): Boolean {
    val value = attribute(parser, "val") ?: return true
    return value == "1" || value == "true" || value == "on"
  }

  private fun highlightColor(value: String): Int? = when (value) {
    "yellow" -> 0xFFFFFF00.toInt()
    "green" -> 0xFF00FF00.toInt()
    "cyan" -> 0xFF00FFFF.toInt()
    "magenta" -> 0xFFFF00FF.toInt()
    "red" -> 0xFFFF0000.toInt()
    "blue" -> 0xFF0000FF.toInt()
    "lightGray" -> 0xFFD3D3D3.toInt()
    "darkGray" -> 0xFFA9A9A9.toInt()
    else -> null
  }

  private fun headingLevelFor(props: ParagraphProps, styles: Map<String, StyleInfo>): Int {
    if (props.styleId.isNotEmpty()) {
      val style = styles[props.styleId]
      val name = style?.name?.lowercase().orEmpty()
      if (name.startsWith("heading")) {
        val digits = name.filter { it.isDigit() }
        return if (digits.isEmpty()) 1 else digits.toInt().coerceIn(1, 6)
      }
      if (name == "title") return 1
      if (style != null && style.outlineLevel >= 0) return (style.outlineLevel + 1).coerceIn(1, 6)
      if (Regex("(?i)^h[1-6]$").matches(props.styleId)) {
        return props.styleId.last().digitToInt()
      }
    }
    if (props.outlineLevel in 0..5) return props.outlineLevel + 1
    return 0
  }

  // MARK: - Other parts

  private fun parseStyles(xml: ByteArray?): Map<String, StyleInfo> {
    if (xml == null) return emptyMap()
    val styles = HashMap<String, StyleInfo>()
    val parser = newParser(xml)
    var currentId = ""
    var currentName = ""
    var outline = -1
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
      if (event == XmlPullParser.START_TAG) {
        when (localName(parser.name)) {
          "style" -> {
            currentId = attribute(parser, "styleId").orEmpty()
            currentName = ""
            outline = -1
          }
          "name" -> if (currentId.isNotEmpty()) {
            currentName = attribute(parser, "val") ?: currentName
          }
          "outlineLvl" -> if (currentId.isNotEmpty()) {
            outline = attribute(parser, "val")?.toIntOrNull() ?: outline
          }
        }
      } else if (event == XmlPullParser.END_TAG && localName(parser.name) == "style") {
        if (currentId.isNotEmpty()) styles[currentId] = StyleInfo(currentName, outline)
      }
      event = parser.next()
    }
    return styles
  }

  private fun parseNumbering(xml: ByteArray?): NumberingTable {
    val abstractLevels = HashMap<String, HashMap<Int, NumberingLevel>>()
    val numbering = HashMap<String, String>()
    if (xml == null) return NumberingTable(abstractLevels, numbering)

    val parser = newParser(xml)
    var abstractId = ""
    var numId = ""
    var level = 0
    var format = ""
    var levelText = ""
    var start = 1

    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
      if (event == XmlPullParser.START_TAG) {
        when (localName(parser.name)) {
          "abstractNum" -> abstractId = attribute(parser, "abstractNumId").orEmpty()
          "num" -> numId = attribute(parser, "numId").orEmpty()
          "abstractNumId" -> if (numId.isNotEmpty()) {
            numbering[numId] = attribute(parser, "val").orEmpty()
          }
          "lvl" -> {
            level = attribute(parser, "ilvl")?.toIntOrNull() ?: 0
            format = ""
            levelText = ""
            start = 1
          }
          "numFmt" -> format = attribute(parser, "val") ?: format
          "lvlText" -> levelText = attribute(parser, "val") ?: levelText
          "start" -> start = attribute(parser, "val")?.toIntOrNull() ?: start
        }
      } else if (event == XmlPullParser.END_TAG && localName(parser.name) == "lvl") {
        if (abstractId.isNotEmpty()) {
          abstractLevels.getOrPut(abstractId) { HashMap() }[level] =
            NumberingLevel(format, levelText, start)
        }
      }
      event = parser.next()
    }
    return NumberingTable(abstractLevels, numbering)
  }

  private fun parseComments(xml: ByteArray?): List<DocxCommentData> {
    if (xml == null) return emptyList()
    val comments = ArrayList<DocxCommentData>()
    val parser = newParser(xml)
    var id = ""
    var author = ""
    var date = ""
    var text: StringBuilder? = null

    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
      if (event == XmlPullParser.START_TAG) {
        when (localName(parser.name)) {
          "comment" -> {
            id = attribute(parser, "id").orEmpty()
            author = attribute(parser, "author").orEmpty()
            date = attribute(parser, "date").orEmpty()
            text = StringBuilder()
          }
          "t" -> if (text != null) text.append(parser.nextText())
        }
      } else if (event == XmlPullParser.END_TAG && localName(parser.name) == "comment") {
        val builder = text
        if (id.isNotEmpty() && builder != null) {
          comments.add(
            DocxCommentData(
              id = id,
              author = author,
              date = date,
              text = builder.toString().trim(),
            ),
          )
        }
        id = ""
        text = null
      }
      event = parser.next()
    }
    return comments
  }

  private fun headerFooterText(entries: Map<String, ByteArray>, prefix: String): String {
    val key = entries.keys
      .filter { it.startsWith("word/$prefix") }
      .sorted()
      .firstOrNull() ?: return ""
    val xml = entries[key] ?: return ""
    val parser = newParser(xml)
    val builder = StringBuilder()
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
      if (event == XmlPullParser.START_TAG && localName(parser.name) == "t") {
        builder.append(parser.nextText())
        if (builder.length > 160) break
      }
      event = parser.next()
    }
    return builder.toString().trim()
  }

  // MARK: - XML helpers

  private fun newParser(xml: ByteArray): XmlPullParser {
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    parser.setInput(ByteArrayInputStream(xml), null)
    return parser
  }

  private fun localName(name: String?): String {
    if (name == null) return ""
    val colon = name.indexOf(':')
    return if (colon >= 0) name.substring(colon + 1) else name
  }

  private fun attribute(parser: XmlPullParser, name: String): String? {
    for (index in 0 until parser.attributeCount) {
      if (localName(parser.getAttributeName(index)) == name) {
        return parser.getAttributeValue(index)
      }
    }
    return null
  }

  private fun twipsToPoints(value: String?, fallback: Float): Float {
    val parsed = value?.toFloatOrNull() ?: return fallback
    return parsed / 20f
  }
}
