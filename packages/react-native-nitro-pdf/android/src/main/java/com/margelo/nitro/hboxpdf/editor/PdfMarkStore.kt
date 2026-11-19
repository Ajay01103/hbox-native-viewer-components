package com.margelo.nitro.hboxpdf.editor

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors

/**
 * Keeps an edit list next to the document so a viewer can be closed, the app
 * killed and the PDF reopened without losing a single mark.
 *
 * The sidecar lives in app storage (the document itself is usually read-only,
 * picked from another app's provider), is keyed by the document's canonical
 * path plus its size and mtime — so a different file at the same path starts
 * clean — and is rewritten atomically on a debounce, never on the UI thread.
 */
internal class PdfMarkStore(context: Context) {
  private val dir = File(context.filesDir, "pdf-markup")
  private val main = Handler(Looper.getMainLooper())
  private val io = Executors.newSingleThreadExecutor()
  private var pending: Runnable? = null
  private var docPath: String? = null
  private var file: File? = null

  /** Marks saved by an earlier session, newest first; empty when there are none. */
  fun open(documentPath: String): List<PdfMark> {
    flush()
    docPath = documentPath
    val target = sidecarFor(documentPath)
    file = target
    if (!target.exists()) return emptyList()
    return try {
      PdfMarkCodec.decodeDocument(target.readText())
    } catch (error: Throwable) {
      // A corrupt sidecar must never stop the document from opening.
      target.delete()
      emptyList()
    }
  }

  /** Coalesces the burst of edits a drag produces into one write. */
  fun schedule(marks: List<PdfMark>) {
    if (file == null) return
    pending?.let { main.removeCallbacks(it) }
    val snapshot = marks.map { it.copy() }
    val runnable = Runnable { write(snapshot) }
    pending = runnable
    main.postDelayed(runnable, SAVE_DELAY_MS)
  }

  /** Writes anything still pending, e.g. when the view goes away. */
  fun flush() {
    val runnable = pending ?: return
    pending = null
    main.removeCallbacks(runnable)
    runnable.run()
  }

  fun detach() {
    flush()
    file = null
    docPath = null
  }

  /** Deletes the sidecar, used when the host clears every annotation. */
  fun clear() {
    pending?.let { main.removeCallbacks(it) }
    pending = null
    val target = file
    if (target != null) {
      io.execute { runCatching { target.delete() } }
    }
  }

  private fun write(marks: List<PdfMark>) {
    val target = file ?: return
    val path = docPath ?: return
    val payload = PdfMarkCodec.encodeDocument(path, marks)
    io.execute {
      try {
        dir.mkdirs()
        if (marks.isEmpty()) {
          if (target.exists()) target.delete()
          return@execute
        }
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(payload)
        if (!temp.renameTo(target)) {
          target.writeText(payload)
          temp.delete()
        }
      } catch (_: Throwable) {
        // Persistence is best effort: an unwritable cache must not break editing.
      }
    }
  }

  private fun sidecarFor(documentPath: String): File {
    val canonical = try {
      File(documentPath).canonicalPath
    } catch (_: Throwable) {
      documentPath
    }
    val source = File(canonical)
    val size = if (source.exists()) source.length() else 0L
    val modified = if (source.exists()) source.lastModified() else 0L
    val digest = sha1("$canonical|$size|$modified")
    dir.mkdirs()
    return File(dir, "$digest.json")
  }

  private fun sha1(value: String): String {
    val bytes = java.security.MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
    val builder = StringBuilder(bytes.size * 2)
    bytes.forEach { byte ->
      val v = byte.toInt() and 0xFF
      if (v < 16) builder.append('0')
      builder.append(Integer.toHexString(v))
    }
    return builder.toString()
  }

  private companion object {
    const val SAVE_DELAY_MS = 600L
  }
}
