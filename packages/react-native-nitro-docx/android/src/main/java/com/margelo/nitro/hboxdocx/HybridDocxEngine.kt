package com.margelo.nitro.hboxdocx

import android.util.Base64
import com.margelo.nitro.core.Promise
import java.io.File
import java.util.UUID

class HybridDocxEngine : HybridDocxEngineSpec() {
  private val engineId: String = UUID.randomUUID().toString()

  @Volatile
  private var session: DocxSession? = null

  @Volatile
  private var cachedInfo: DocInfo? = null

  override val id: String
    get() = engineId

  override val pageCount: Double
    get() = session?.layout("markup", false)?.pageCount?.toDouble() ?: 0.0

  override fun load(source: DocxSource): Promise<DocInfo> = Promise.async {
    val bytes = readSource(source)
    if (bytes.isEmpty()) {
      val origin = source.uri?.takeIf { it.isNotEmpty() } ?: "inline base64 data"
      throw DocxParseException("CORRUPT: the file is empty ($origin)")
    }
    val fileName = source.uri
      ?.substringAfterLast('/')
      ?.takeIf { it.isNotEmpty() }
      ?: "document.docx"

    val model = DocxParser().parse(bytes, fileName)
    val next = DocxSession(model)
    session = next
    DocxEngineRegistry.store(engineId, next)

    val layout = next.layout("markup", false)
    val info = buildInfo(model, layout)
    cachedInfo = info
    info
  }

  override fun getPageSize(): PageSize {
    val model = requireSession().model
    return PageSize(model.geometry.width.toDouble(), model.geometry.height.toDouble())
  }

  override fun search(query: String): Promise<Array<SearchHit>> = Promise.async {
    val layout = requireSession().layout("markup", false)
    val paragraphs = requireSession().model.paragraphs
    layout.search(query, 200).map { (page, payload) ->
      val (offset, snippet) = payload
      val anchor = paragraphs.lastOrNull { layout.pageForAnchor(it.anchorId) == page }?.anchorId ?: ""
      SearchHit(
        page = page.toDouble(),
        anchorId = anchor.ifEmpty { "p-${offset}" },
        snippet = snippet,
      )
    }.toTypedArray()
  }

  override fun getText(fromPage: Double?, toPage: Double?): Promise<String> = Promise.async {
    val layout = requireSession().layout("markup", false)
    val start = (fromPage?.toInt() ?: 1).coerceIn(1, maxOf(1, layout.pageCount))
    val end = (toPage?.toInt() ?: layout.pageCount).coerceIn(start, maxOf(1, layout.pageCount))
    (start..end).joinToString("\n\n") { layout.pageText(it - 1).trim() }
  }

  override fun getPageText(page: Double): Promise<String> = Promise.async {
    val layout = requireSession().layout("markup", false)
    layout.pageText(page.toInt())
  }

  override fun getOutline(): Promise<Array<OutlineItem>> = Promise.async {
    ensureInfo().outline
  }

  override fun getComments(): Promise<Array<DocComment>> = Promise.async {
    ensureInfo().comments
  }

  override fun dispose() {
    DocxEngineRegistry.remove(engineId)
    session?.clear()
    session = null
    cachedInfo = null
    super.dispose()
  }

  private fun requireSession(): DocxSession =
    session ?: throw IllegalStateException("No DOCX document is open")

  private fun ensureInfo(): DocInfo {
    cachedInfo?.let { return it }
    val current = requireSession()
    val info = buildInfo(current.model, current.layout("markup", false))
    cachedInfo = info
    return info
  }

  private fun buildInfo(model: DocxModel, layout: DocxLayout): DocInfo {
    val outline = model.outline.map { entry ->
      OutlineItem(
        id = entry.id,
        title = entry.title,
        level = entry.level.toDouble(),
        page = layout.pageForAnchor(entry.id).toDouble(),
      )
    }

    val comments = model.comments.map { comment ->
      val anchor = model.paragraphs.firstOrNull { paragraph ->
        paragraph.runs.any { run -> run.commentIds.contains(comment.id) }
      }
      DocComment(
        id = comment.id,
        author = comment.author,
        date = comment.date.ifEmpty { null },
        text = comment.text,
        anchorId = anchor?.anchorId ?: "",
        page = layout.pageForAnchor(anchor?.anchorId ?: "").toDouble(),
        parentId = null,
      )
    }

    val warnings = model.warnings.toMutableList()
    if (model.paragraphs.isEmpty() && warnings.isEmpty()) {
      warnings.add("No readable text was found in this document")
    }

    return DocInfo(
      fileName = model.fileName,
      title = model.title,
      pageCountFinal = true,
      pageCount = layout.pageCount.toDouble(),
      pageSize = PageSize(model.geometry.width.toDouble(), model.geometry.height.toDouble()),
      outline = outline.toTypedArray(),
      comments = comments.toTypedArray(),
      hasTrackedChanges = model.hasTrackedChanges,
      warnings = warnings.toTypedArray(),
    )
  }

  private fun readSource(source: DocxSource): ByteArray {
    val uri = source.uri
    if (uri != null && uri.startsWith("content://")) {
      // Content URIs cannot be opened as a File; pass the bytes from JS instead.
      throw DocxParseException("CORRUPT: cannot read a content:// URI directly ($uri)")
    }
    if (!uri.isNullOrEmpty()) {
      val path = if (uri.startsWith("file://")) uri.substring("file://".length) else uri
      val file = File(path)
      if (!file.exists()) {
        throw DocxParseException("CORRUPT: could not read $path")
      }
      if (file.length() > 512L * 1024 * 1024) {
        throw DocxParseException("TOO_LARGE: the file is larger than 512 MB")
      }
      return file.readBytes()
    }
    val base64 = source.base64
    if (!base64.isNullOrEmpty()) {
      return Base64.decode(base64, Base64.DEFAULT)
    }
    throw DocxParseException("CORRUPT: no uri or base64 data was provided")
  }
}
