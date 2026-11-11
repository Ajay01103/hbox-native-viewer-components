package com.margelo.nitro.hboxdocx

import java.util.concurrent.ConcurrentHashMap

/** A parsed document plus its lazily built paginations. */
internal class DocxSession(val model: DocxModel) {
  private val layouts = ConcurrentHashMap<String, DocxLayout>()

  fun layout(mode: String, dark: Boolean): DocxLayout {
    val normalized = normalizeMode(mode)
    val key = "$normalized:$dark"
    return layouts.getOrPut(key) { DocxLayout.build(model, normalized, dark) }
  }

  fun clear() {
    layouts.clear()
  }

  companion object {
    fun normalizeMode(mode: String): String =
      when (mode) {
        "final", "original" -> mode
        else -> "markup"
      }
  }
}

/** Holds every open document keyed by the id exposed to JS. */
internal object DocxEngineRegistry {
  private val sessions = ConcurrentHashMap<String, DocxSession>()

  fun store(id: String, session: DocxSession) {
    sessions[id] = session
  }

  fun get(id: String): DocxSession? = sessions[id]

  fun remove(id: String) {
    sessions.remove(id)?.clear()
  }
}
