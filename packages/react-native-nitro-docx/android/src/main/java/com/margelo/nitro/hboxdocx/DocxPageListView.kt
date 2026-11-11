package com.margelo.nitro.hboxdocx

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/**
 * Virtualized vertical page scroller. One lightweight view per visible page;
 * native owns scrolling, so no frames cross the JS bridge.
 */
class DocxPageListView(context: Context) : FrameLayout(context) {
  private val recycler = RecyclerView(context)
  private val pageAdapter = PageAdapter()

  var onPageChange: ((Int, Int) -> Unit)? = null
  var onChromeVisibility: ((Boolean) -> Unit)? = null
  var onTextCopied: (() -> Unit)? = null
  var onLayoutProgress: ((Int, Boolean) -> Unit)? = null

  private var chromeVisible = true
  private var scrollAccumulator = 0

  /** Pagination is expensive; build it off the UI thread and coalesce bursts. */
  private val layoutExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "hbox-docx-layout").apply { isDaemon = true }
  }
  private val mainHandler = Handler(Looper.getMainLooper())
  private var reloadGeneration = 0

  var documentId: String = ""
    set(value) {
      field = value
      pageAdapter.documentId = value
      pageAdapter.reload()
      if (value.isNotEmpty()) recycler.scrollToPosition(0)
    }

  var pageScale: Float = 1f
    set(value) {
      field = value
      pageAdapter.pageScale = value
      pageAdapter.reload()
    }

  var mode: String = "markup"
    set(value) {
      field = value
      pageAdapter.mode = DocxSession.normalizeMode(value)
      pageAdapter.reload()
    }

  var palette: DocxPalette? = null
    set(value) {
      field = value
      pageAdapter.palette = value
      pageAdapter.reload()
      setBackgroundColor(parseDocxColor(value?.appBg, Color.LTGRAY))
    }

  init {
    setBackgroundColor(Color.parseColor("#f4f4f5"))
    recycler.layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
    recycler.adapter = pageAdapter
    recycler.itemAnimator = null
    recycler.setHasFixedSize(true)
    recycler.setItemViewCacheSize(3)
    recycler.setPadding(0, 12, 0, 24)
    recycler.clipToPadding = false
    addView(
      recycler,
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
    )

    recycler.addOnScrollListener(
      object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
          val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
          val firstVisible = manager.findFirstVisibleItemPosition()
          if (firstVisible != RecyclerView.NO_POSITION) {
            onPageChange?.invoke(firstVisible + 1, pageAdapter.itemCount)
          }
          updateChromeVisibility(dy)
        }
      },
    )
  }

  fun scrollToPage(pageIndex: Int, animated: Boolean) {
    if (pageIndex < 0 || pageIndex >= pageAdapter.itemCount) return
    if (animated) {
      recycler.smoothScrollToPosition(pageIndex)
    } else {
      recycler.scrollToPosition(pageIndex)
    }
  }

  fun refresh() {
    pageAdapter.reload()
  }

  private fun updateChromeVisibility(dy: Int) {
    if (dy > 3 || dy < -3) scrollAccumulator += dy
    if (scrollAccumulator > 160 && chromeVisible) {
      chromeVisible = false
      scrollAccumulator = 0
      onChromeVisibility?.invoke(false)
    } else if (scrollAccumulator < -160 && !chromeVisible) {
      chromeVisible = true
      scrollAccumulator = 0
      onChromeVisibility?.invoke(true)
    }
  }

  private fun copyPageText(pageIndex: Int) {
    val session = DocxEngineRegistry.get(documentId) ?: return
    val layout = session.layout(mode, palette?.pageDarkMode == true)
    val text = layout.pageText(pageIndex)
    if (text.isBlank()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("Page ${pageIndex + 1}", text))
    onTextCopied?.invoke()
  }

  private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {
    var documentId: String = ""
    var pageScale: Float = 1f
    var mode: String = "markup"
    var palette: DocxPalette? = null

    private var pageCount = 0

    fun reload() {
      val session = DocxEngineRegistry.get(documentId)
      if (session == null) {
        applyPages(0)
        return
      }

      val requestedMode = mode
      val requestedDark = palette?.pageDarkMode == true
      val generation = ++reloadGeneration

      layoutExecutor.execute {
        val count = try {
          session.layout(requestedMode, requestedDark).pageCount
        } catch (error: Throwable) {
          // A document that fails to paginate shows zero pages instead of
          // crashing the adapter (and with it the whole screen).
          0
        }
        mainHandler.post {
          // Drop stale work: a newer document/mode/palette superseded it.
          if (generation == reloadGeneration) applyPages(count)
        }
      }
    }

    private fun applyPages(count: Int) {
      if (pageCount == count) return
      pageCount = count
      notifyDataSetChanged()
      if (pageCount > 0) onLayoutProgress?.invoke(pageCount, true)
    }

    override fun getItemCount(): Int = pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
      val view = DocxPageView(parent.context)
      view.layoutParams = RecyclerView.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        (800 * pageScale).toInt().coerceAtLeast(1),
      )
      return PageHolder(view)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
      val view = holder.pageView
      view.documentId = documentId
      view.pageIndex = position
      view.mode = mode
      view.palette = palette

      val session = DocxEngineRegistry.get(documentId)
      val geometry = session?.model?.geometry
      val height = if (geometry != null) (geometry.height * pageScale).toInt() else 800
      val params = view.layoutParams
      if (params != null && params.height != height.coerceAtLeast(1)) {
        params.height = height.coerceAtLeast(1)
        view.layoutParams = params
      }
      view.setOnLongClickListener {
        copyPageText(position)
        true
      }
      view.invalidate()
    }
  }

  private class PageHolder(val pageView: DocxPageView) : RecyclerView.ViewHolder(pageView)
}
