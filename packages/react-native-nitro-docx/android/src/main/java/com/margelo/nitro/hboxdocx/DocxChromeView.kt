package com.margelo.nitro.hboxdocx

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray

/**
 * The complete DOCX viewer: native toolbar, native tab strip, native
 * page/outline/comment panels and the existing native page canvas.
 *
 * React keeps the engine, the zoom mode and the theme, and hands the values the
 * chrome displays down as props; every control reports back through a callback,
 * so behaviour is unchanged while the chrome itself no longer needs a single
 * React view.
 */
internal class DocxChromeView(context: Context) : FrameLayout(context) {
  var onPageChange: ((Int, Int) -> Unit)? = null
  var onZoomSettled: ((Float) -> Unit)? = null
  var onZoomOut: (() -> Unit)? = null
  var onZoomIn: (() -> Unit)? = null
  var onZoomReset: (() -> Unit)? = null
  var onPrevious: (() -> Unit)? = null
  var onNext: (() -> Unit)? = null
  var onToggleTheme: (() -> Unit)? = null
  var onUpload: (() -> Unit)? = null
  var onDownload: (() -> Unit)? = null
  var onCycleChanges: (() -> Unit)? = null
  var onViewport: ((widthDp: Float, heightDp: Float) -> Unit)? = null

  private val density = resources.displayMetrics.density
  private val column = LinearLayout(context)
  private val listView = DocxPageListView(context)
  private var toolbar: View = LinearLayout(context)
  // The chips that change while scrolling are kept and updated in place: a page
  // change used to rebuild the whole bar, which flickered mid-scroll.
  private var pageLabelView: TextView? = null
  private var previousChipView: View? = null
  private var nextChipView: View? = null
  private var zoomChipView: TextView? = null
  private var tabs: LinearLayout = LinearLayout(context)
  private val toast = TextView(context)
  private var panel: View? = null

  private var activePanel = "none"
  private var layoutFinal = true
  private var refreshPosted = false
  private var relayoutQueued = false
  private var inRelayout = false

  // Props mirrored from JS.
  private var documentId = ""
  private var baseScale = 1f
  private var jumpPage = 1
  private var jumpNonce = 0
  private var palette: DocxPalette? = null
  private var mode = "markup"
  private var fileName = ""
  private var page = 1
  private var pageCount = 0
  private var zoomLabel = ""
  private var isDark = false
  private var showUpload = true
  private var showDownload = true
  private var showThumbnails = true
  private var showOutline = true
  private var showComments = true
  private var showChanges = false
  private var outlineJson = "[]"
  private var commentsJson = "[]"

  init {
    setBackgroundColor(Color.parseColor("#f4f4f5"))
    column.orientation = LinearLayout.VERTICAL
    addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

    toolbar = buildToolbar()
    tabs = buildTabs()
    column.addView(toolbar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    column.addView(tabs, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    column.addView(listView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

    toast.setTextColor(Color.WHITE)
    toast.textSize = 12f
    toast.typeface = Typeface.create("sans-serif", Typeface.BOLD)
    toast.setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
    toast.alpha = 0f
    addView(toast, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
      bottomMargin = dp(20f)
    })

    listView.onPageChange = { current, total ->
      page = current
      pageCount = total
      updateToolbarChips()
      if (activePanel == "pages") rebuildPanel()
      onPageChange?.invoke(current, total)
    }
    listView.onLayoutProgress = { _, final ->
      if (layoutFinal != final) {
        layoutFinal = final
        rebuildTabs()
      }
    }
    listView.onTextCopied = { showToast("Text copied") }
    listView.onZoomSettled = { value -> onZoomSettled?.invoke(value) }
    // React resolves fit modes from the page canvas size, which only the native
    // chrome knows once it has laid out its bars.
    listView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
      val width = (right - left).toFloat()
      val height = (bottom - top).toFloat()
      if (width <= 0f || height <= 0f) return@addOnLayoutChangeListener
      if (width == (oldRight - oldLeft).toFloat() && height == (oldBottom - oldTop).toFloat()) return@addOnLayoutChangeListener
      onViewport?.invoke(width / density, height / density)
    }
  }

  // ---- props --------------------------------------------------------------

  fun setDocumentId(value: String) {
    if (documentId == value) return
    documentId = value
    listView.documentId = value
  }

  fun setScale(value: Float) {
    val next = value.coerceIn(0.05f, 12f)
    if (baseScale == next) return
    baseScale = next
    listView.pageScale = next
  }

  fun setJump(pageNumber: Int, nonce: Int) {
    jumpPage = pageNumber
    if (jumpNonce != nonce) {
      jumpNonce = nonce
      listView.scrollToPage(pageNumber - 1, animated = false)
    }
  }

  fun setPalette(value: DocxPalette) {
    palette = value
    isDark = value.pageDarkMode
    setBackgroundColor(color(value.appBg, Color.parseColor("#f4f4f5")))
    listView.palette = value
    scheduleRefresh()
  }

  fun setTrackedChanges(value: String) {
    val normalized = DocxSession.normalizeMode(value)
    if (mode == normalized) return
    mode = normalized
    listView.mode = normalized
    rebuildTabs()
  }

  fun setFileName(value: String) {
    if (fileName == value) return
    fileName = value
    rebuildToolbar()
  }

  fun setPage(value: Int, total: Int) {
    page = value.coerceAtLeast(1)
    pageCount = total.coerceAtLeast(0)
    updateToolbarChips()
  }

  fun setZoomLabel(value: String) {
    if (zoomLabel == value) return
    zoomLabel = value
    updateToolbarChips()
  }

  fun setDarkMode(value: Boolean) {
    if (isDark == value) return
    isDark = value
    rebuildToolbar()
  }

  fun setVisibilityFlags(upload: Boolean, download: Boolean) {
    if (showUpload == upload && showDownload == download) return
    showUpload = upload
    showDownload = download
    rebuildToolbar()
  }

  fun setTabs(thumbnails: Boolean, outline: Boolean, comments: Boolean, changes: Boolean) {
    if (showThumbnails == thumbnails && showOutline == outline && showComments == comments && showChanges == changes) return
    showThumbnails = thumbnails
    showOutline = outline
    showComments = comments
    showChanges = changes
    if (activePanel == "pages" && !thumbnails) activePanel = "none"
    if (activePanel == "outline" && !outline) activePanel = "none"
    if (activePanel == "comments" && !comments) activePanel = "none"
    rebuildTabs()
    if (activePanel == "none") removePanel() else rebuildPanel()
  }

  fun setOutline(json: String) {
    if (outlineJson == json) return
    outlineJson = json
    if (activePanel == "outline") rebuildPanel()
    rebuildTabs()
  }

  fun setComments(json: String) {
    if (commentsJson == json) return
    commentsJson = json
    if (activePanel == "comments") rebuildPanel()
    rebuildTabs()
  }

  fun release() {
    listView.documentId = ""
  }

  // ---- chrome -------------------------------------------------------------

  private fun scheduleRefresh() {
    if (refreshPosted) return
    refreshPosted = true
    post {
      refreshPosted = false
      rebuildToolbar()
      rebuildTabs()
      if (activePanel != "none") rebuildPanel()
    }
  }

  private fun replaceBar(index: Int, view: View) {
    column.removeViewAt(index)
    column.addView(view, index)
    relayout()
  }

  /**
   * Fabric lays this view out from Yoga and swallows `requestLayout()` coming
   * from anything below the React root, so a subtree rebuilt after the layout
   * pass would stay at 0x0 forever. Re-measuring against our current bounds puts
   * the new toolbar/tabs/panel on screen immediately.
   */
  internal fun relayout() {
    if (inRelayout || isInLayout) return
    if (width <= 0 || height <= 0) return
    inRelayout = true
    measure(
      MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
      MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
    )
    layout(left, top, right, bottom)
    inRelayout = false
  }

  override fun requestLayout() {
    super.requestLayout()
    if (inRelayout || relayoutQueued) return
    relayoutQueued = true
    post {
      relayoutQueued = false
      relayout()
    }
  }

  private fun rebuildToolbar() {
    val next = buildToolbar()
    toolbar = next
    replaceBar(0, next)
  }

  /** Page and zoom chips change while scrolling; update them in place. */
  private fun updateToolbarChips() {
    pageLabelView?.text = "$page / ${pageCount.coerceAtLeast(1)}"
    previousChipView?.let { view ->
      view.isEnabled = page > 1
      view.alpha = if (page > 1) 1f else 0.45f
    }
    nextChipView?.let { view ->
      view.isEnabled = page < pageCount
      view.alpha = if (page < pageCount) 1f else 0.45f
    }
    zoomChipView?.text = zoomLabel.ifBlank { "Fit width" }
  }

  private fun rebuildTabs() {
    val next = buildTabs()
    tabs = next
    if (column.childCount < 2) {
      column.addView(next, 1)
    } else {
      replaceBar(1, next)
    }
  }

  /**
   * One row of chips, scrollable sideways: a large display font size can make
   * the controls wider than the screen, and clipping "Open" mid-word is worse
   * than a row that scrolls.
   */
  private fun buildToolbar(): View {
    val theme = palette
    val textColor = color(theme?.text, Color.parseColor("#18181b"))
    val muted = color(theme?.muted, Color.parseColor("#71717a"))
    val border = color(theme?.pageBorder, Color.parseColor("#e4e4e7"))

    val bar = LinearLayout(context)
    bar.orientation = LinearLayout.HORIZONTAL
    bar.gravity = Gravity.CENTER_VERTICAL
    bar.setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
    bar.setBackgroundColor(color(theme?.appBg, Color.parseColor("#f4f4f5")))
    val title = label(fileName.ifBlank { "Document" }, 12f, textColor, Typeface.BOLD)
    title.maxLines = 1
    title.ellipsize = TextUtils.TruncateAt.END
    bar.addView(title, LinearLayout.LayoutParams(dp(96f), LayoutParams.WRAP_CONTENT))

    // Zoom: - [label] +
    bar.addView(chip("−", 10.5f, textColor, border) { onZoomOut?.invoke() }, chipParams(24, 28, 4f))
    val zoomChip = chip(zoomLabel.ifBlank { "Fit width" }, 10.5f, textColor, border) { onZoomReset?.invoke() }
    zoomChipView = zoomChip
    bar.addView(zoomChip, chipParams(54, 28, 2f))
    bar.addView(chip("+", 10.5f, textColor, border) { onZoomIn?.invoke() }, chipParams(24, 28, 2f))

    // Page navigation.
    val previous = chip("‹", 10.5f, textColor, border) { onPrevious?.invoke() }
    previous.isEnabled = page > 1
    previous.alpha = if (page > 1) 1f else 0.45f
    previousChipView = previous
    bar.addView(previous, chipParams(24, 28, 4f))
    val pageLabel = label("$page / ${pageCount.coerceAtLeast(1)}", 10.5f, muted, Typeface.NORMAL)
    pageLabel.gravity = Gravity.CENTER
    pageLabel.maxLines = 1
    pageLabelView = pageLabel
    bar.addView(pageLabel, chipParams(38, 28, 2f))
    val next = chip("›", 10.5f, textColor, border) { onNext?.invoke() }
    next.isEnabled = page < pageCount
    next.alpha = if (page < pageCount) 1f else 0.45f
    nextChipView = next
    bar.addView(next, chipParams(24, 28, 2f))

    bar.addView(chip(if (isDark) "Light" else "Dark", 10.5f, textColor, border) { onToggleTheme?.invoke() }, wrapChipParams(4f))
    if (showUpload) {
      bar.addView(chip("Open", 10.5f, textColor, border) { onUpload?.invoke() }, wrapChipParams(2f))
    }
    if (showDownload) {
      bar.addView(chip("Save", 10.5f, textColor, border) { onDownload?.invoke() }, wrapChipParams(2f))
    }
    val scroll = android.widget.HorizontalScrollView(context)
    scroll.isHorizontalScrollBarEnabled = false
    scroll.addView(bar, ViewGroup.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    return scroll
  }

  private fun buildTabs(): LinearLayout {
    val theme = palette
    val textColor = color(theme?.text, Color.parseColor("#18181b"))
    val muted = color(theme?.muted, Color.parseColor("#71717a"))
    val border = color(theme?.pageBorder, Color.parseColor("#e4e4e7"))

    val row = LinearLayout(context)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    row.setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
    row.setBackgroundColor(color(theme?.appBg, Color.parseColor("#f4f4f5")))

    var anyTab = false
    if (showThumbnails) {
      anyTab = true
      row.addView(tab("Pages", activePanel == "pages", textColor, muted, border) { togglePanel("pages") }, tabParams(0f))
    }
    if (showOutline) {
      anyTab = true
      row.addView(tab("Outline", activePanel == "outline", textColor, muted, border) { togglePanel("outline") }, tabParams(6f))
    }
    if (showComments) {
      anyTab = true
      val count = commentCount()
      val title = if (count > 0) "Comments ($count)" else "Comments"
      row.addView(tab(title, activePanel == "comments", textColor, muted, border) { togglePanel("comments") }, tabParams(6f))
    }
    if (showChanges) {
      anyTab = true
      val label = when (mode) {
        "final" -> "Changes: final"
        "original" -> "Changes: original"
        else -> "Changes: markup"
      }
      row.addView(tab(label, mode != "markup", textColor, muted, border) { onCycleChanges?.invoke() }, tabParams(6f))
    }
    if (!layoutFinal) {
      anyTab = true
      row.addView(tab("Laying out…", false, textColor, muted, border) { }, tabParams(6f))
    }

    // A hairline under the strip; the row disappears entirely when there is
    // nothing to show, so the page canvas keeps the space.
    val holder = LinearLayout(context)
    holder.orientation = LinearLayout.VERTICAL
    holder.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    holder.addView(borderStrip(border), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 1))
    holder.visibility = if (anyTab) View.VISIBLE else View.GONE
    return holder
  }

  private fun borderStrip(color: Int): View = View(context).apply {
    setBackgroundColor(color)
    layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 1)
  }

  private fun label(text: String, sizeSp: Float, color: Int, style: Int): TextView {
    return TextView(context).apply {
      this.text = text
      textSize = sizeSp
      setTextColor(color)
      typeface = Typeface.create("sans-serif", style)
      includeFontPadding = false
    }
  }

  private fun chipParams(widthDp: Int, heightDp: Int, extraTop: Float): LinearLayout.LayoutParams {
    return LinearLayout.LayoutParams(dp(widthDp), dp(heightDp)).apply {
      leftMargin = dp(extraTop)
    }
  }

  private fun tabParams(extraLeft: Float): LinearLayout.LayoutParams {
    return LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
      leftMargin = dp(extraLeft)
    }
  }

  private fun wrapChipParams(extraTop: Float): LinearLayout.LayoutParams {
    return LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(28f)).apply {
      leftMargin = dp(extraTop)
    }
  }

  private fun chip(
    text: String,
    sizeSp: Float,
    color: Int,
    border: Int,
    onClick: () -> Unit,
  ): TextView {
    return TextView(context).apply {
      this.text = text
      textSize = sizeSp
      typeface = Typeface.create("sans-serif", Typeface.BOLD)
      setTextColor(color)
      gravity = Gravity.CENTER
      isClickable = true
      isFocusable = true
      includeFontPadding = false
      maxLines = 1
      ellipsize = TextUtils.TruncateAt.END
      setPadding(dp(2f), 0, dp(2f), 0)
      background = rounded(0x00000000, 6f, border, 0.8f)
      setOnClickListener { onClick() }
    }
  }

  private fun tab(
    text: String,
    active: Boolean,
    textColor: Int,
    muted: Int,
    border: Int,
    onClick: () -> Unit,
  ): TextView {
    return TextView(context).apply {
      this.text = text
      textSize = 12f
      typeface = Typeface.create("sans-serif", Typeface.BOLD)
      setTextColor(if (active) textColor else muted)
      setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
      isClickable = true
      background = rounded(if (active) border else 0x00000000, 6f, 0, 0f)
      setOnClickListener { onClick() }
    }
  }

  // ---- panels -------------------------------------------------------------

  private fun togglePanel(name: String) {
    if (activePanel == name) {
      activePanel = "none"
      removePanel()
      rebuildTabs()
      return
    }
    openPanel(name)
  }

  private fun openPanel(name: String) {
    activePanel = name
    rebuildTabs()
    rebuildPanel()
  }

  private fun removePanel() {
    panel?.let { removeView(it) }
    panel = null
    relayout()
  }

  private fun rebuildPanel() {
    removePanel()
    if (activePanel == "none") return
    val theme = palette ?: return
    val textColor = color(theme.text, Color.BLACK)
    val muted = color(theme.muted, Color.GRAY)
    val border = color(theme.pageBorder, Color.LTGRAY)
    val panelBg = Color.parseColor(if (theme.pageDarkMode) "#232326" else "#ffffff")

    val overlay = FrameLayout(context)
    val backdrop = View(context)
    backdrop.setBackgroundColor(0x40000000)
    backdrop.setOnClickListener {
      activePanel = "none"
      removePanel()
      rebuildTabs()
    }
    overlay.addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

    val card = LinearLayout(context)
    card.orientation = LinearLayout.VERTICAL
    card.background = rounded(panelBg, 12f, border, 0.8f)
    card.elevation = dp(10f).toFloat()
    overlay.addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
      topMargin = dp(88f) + toolbar.height
      leftMargin = dp(16f)
      rightMargin = dp(16f)
    })

    val header = LinearLayout(context)
    header.orientation = LinearLayout.HORIZONTAL
    header.gravity = Gravity.CENTER_VERTICAL
    header.setPadding(dp(14f), dp(10f), dp(8f), dp(10f))
    val title = label(panelTitle(), 14f, textColor, Typeface.BOLD)
    header.addView(title, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    val close = label("✕", 16f, muted, Typeface.NORMAL)
    close.setPadding(dp(6f), dp(2f), dp(6f), dp(2f))
    close.isClickable = true
    close.setOnClickListener {
      activePanel = "none"
      removePanel()
      rebuildTabs()
    }
    header.addView(close)
    card.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    card.addView(borderStrip(border), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 1))

    val scroller = ScrollView(context)
    scroller.isFillViewport = false
    scroller.isVerticalScrollBarEnabled = false
    val content = LinearLayout(context)
    content.orientation = LinearLayout.VERTICAL
    content.setPadding(dp(8f), dp(6f), dp(8f), dp(10f))
    scroller.addView(content, ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    card.addView(scroller, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

    when (activePanel) {
      "pages" -> buildPages(content, theme, textColor, muted, border)
      "outline" -> buildOutline(content, textColor, muted)
      "comments" -> buildComments(content, textColor, muted, border)
    }

    // Keep the panel on screen: cap the body and let it scroll instead of
    // running past the top of the display.
    val maxHeight = (resources.displayMetrics.heightPixels * 0.7f).toInt()
    overlay.post {
      if (card.height > maxHeight) {
        val headerHeight = card.getChildAt(0).height
        val fixed = (maxHeight - headerHeight - 1).coerceAtLeast(dp(120f))
        scroller.layoutParams = scroller.layoutParams.apply { height = fixed }
      }
    }

    panel = overlay
    addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    relayout()
  }

  private fun panelTitle(): String = when (activePanel) {
    "pages" -> "Pages"
    "outline" -> "Outline"
    "comments" -> "Comments"
    else -> ""
  }

  private fun buildPages(
    content: LinearLayout,
    theme: DocxPalette,
    textColor: Int,
    muted: Int,
    border: Int,
  ) {
    val session = DocxEngineRegistry.get(documentId) ?: return
    val layout = try {
      session.layout(mode, theme.pageDarkMode)
    } catch (_: Throwable) {
      null
    }
    val count = layout?.pageCount ?: pageCount
    if (count <= 0) return
    val geometry = session.model.geometry
    val thumbWidthDp = 112f
    val scale = if (geometry.width > 0f) thumbWidthDp / geometry.width else 0.2f
    val thumbHeightDp = (geometry.height * scale).coerceAtLeast(24f)

    var row: LinearLayout? = null
    for (index in 0 until count) {
      if (index % 2 == 0) {
        row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(4f), 0, dp(4f))
        content.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
      }
      val target = row ?: continue
      val item = LinearLayout(context)
      item.orientation = LinearLayout.VERTICAL
      item.gravity = Gravity.CENTER_HORIZONTAL
      item.setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
      val highlight = index + 1 == page
      item.background = rounded(
        Color.TRANSPARENT,
        8f,
        if (highlight) Color.parseColor("#3b82f6") else border,
        if (highlight) 2f else 0.8f,
      )
      item.isClickable = true
      item.setOnClickListener {
        listView.scrollToPage(index, animated = false)
        activePanel = "none"
        removePanel()
        rebuildTabs()
      }
      val thumb = DocxThumbnailView(context)
      thumb.documentId = documentId
      thumb.pageIndex = index
      thumb.pageScale = scale
      thumb.palette = theme
      item.addView(thumb, LinearLayout.LayoutParams(dp(thumbWidthDp), dp(thumbHeightDp)))
      val caption = label("${index + 1}", 11f, if (highlight) Color.parseColor("#3b82f6") else muted, Typeface.NORMAL)
      caption.gravity = Gravity.CENTER
      item.addView(caption, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(4f)
      })
      target.addView(item, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
        leftMargin = dp(5f)
        rightMargin = dp(5f)
      })
    }
  }

  private fun buildOutline(content: LinearLayout, textColor: Int, muted: Int) {
    val items = outlineRows()
    if (items.isEmpty()) {
      content.addView(emptyLabel("This document has no headings.", muted))
      return
    }
    items.forEach { item ->
      val row = LinearLayout(context)
      row.orientation = LinearLayout.HORIZONTAL
      row.gravity = Gravity.CENTER_VERTICAL
      row.setPadding(dp(12f + (item.level - 1).coerceIn(0, 5) * 14f), dp(9f), dp(14f), dp(9f))
      row.isClickable = true
      row.setOnClickListener {
        listView.scrollToPage(item.page - 1, animated = false)
        activePanel = "none"
        removePanel()
        rebuildTabs()
      }
      val title = label(item.title, 13f, textColor, Typeface.NORMAL)
      title.maxLines = 2
      title.ellipsize = TextUtils.TruncateAt.END
      row.addView(title, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
      row.addView(label("${item.page}", 11f, muted, Typeface.NORMAL), LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
        leftMargin = dp(10f)
      })
      content.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
  }

  private fun buildComments(content: LinearLayout, textColor: Int, muted: Int, border: Int) {
    val items = commentRows()
    if (items.isEmpty()) {
      content.addView(emptyLabel("This document has no comments.", muted))
      return
    }
    items.forEach { item ->
      val card = LinearLayout(context)
      card.orientation = LinearLayout.VERTICAL
      card.setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
      card.background = rounded(Color.TRANSPARENT, 8f, border, 0.8f)
      card.isClickable = true
      card.setOnClickListener {
        listView.scrollToPage(item.page - 1, animated = false)
        activePanel = "none"
        removePanel()
        rebuildTabs()
      }
      val meta = item.author.ifBlank { "Unknown" } + if (item.date.length >= 10) " · ${item.date.substring(0, 10)}" else ""
      card.addView(label(meta, 12f, textColor, Typeface.BOLD))
      val body = label(item.text, 13f, textColor, Typeface.NORMAL)
      body.setPadding(0, dp(4f), 0, dp(4f))
      card.addView(body)
      card.addView(label("page ${item.page}", 11f, muted, Typeface.NORMAL))
      content.addView(card, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        leftMargin = dp(2f)
        rightMargin = dp(2f)
        topMargin = dp(5f)
        bottomMargin = dp(5f)
      })
    }
  }

  private fun emptyLabel(text: String, color: Int): TextView {
    return label(text, 13f, color, Typeface.NORMAL).apply {
      gravity = Gravity.CENTER
      setPadding(dp(20f), dp(20f), dp(20f), dp(20f))
    }
  }

  // ---- json helpers -------------------------------------------------------

  private data class OutlineRow(val title: String, val level: Int, val page: Int)

  private data class CommentRow(val author: String, val date: String, val text: String, val page: Int)

  private fun outlineRows(): List<OutlineRow> = try {
    val array = JSONArray(outlineJson)
    buildList {
      for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        add(
          OutlineRow(
            title = item.optString("title"),
            level = item.optInt("level", 1),
            page = item.optInt("page", 1),
          ),
        )
      }
    }
  } catch (_: Throwable) {
    emptyList()
  }

  private fun commentRows(): List<CommentRow> = try {
    val array = JSONArray(commentsJson)
    buildList {
      for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        add(
          CommentRow(
            author = item.optString("author"),
            date = item.optString("date"),
            text = item.optString("text"),
            page = item.optInt("page", 1),
          ),
        )
      }
    }
  } catch (_: Throwable) {
    emptyList()
  }

  private fun commentCount(): Int = try {
    JSONArray(commentsJson).length()
  } catch (_: Throwable) {
    0
  }

  // ---- feedback & helpers -------------------------------------------------

  private fun showToast(message: String) {
    toast.text = message
    toast.background = rounded(color(palette?.text, Color.BLACK), 999f, 0, 0f)
    toast.animate().cancel()
    toast.animate().alpha(1f).setDuration(140L).start()
    toast.postDelayed({ toast.animate().alpha(0f).setDuration(220L).start() }, 1800L)
  }

  private fun dp(value: Float): Int = (value * density).toInt().coerceAtLeast(0)

  private fun dp(value: Int): Int = (value * density).toInt().coerceAtLeast(0)

  private fun color(value: String?, fallback: Int): Int {
    return try {
      if (value.isNullOrBlank()) fallback else Color.parseColor(value)
    } catch (_: Throwable) {
      fallback
    }
  }

  private fun rounded(fill: Int, radiusDp: Float, strokeColor: Int, strokeWidthDp: Float): GradientDrawable {
    return GradientDrawable().apply {
      shape = GradientDrawable.RECTANGLE
      setColor(fill)
      cornerRadius = dp(radiusDp).toFloat()
      if (strokeColor != 0 && strokeWidthDp > 0f) {
        setStroke((strokeWidthDp * density).toInt().coerceAtLeast(1), strokeColor)
      }
    }
  }
}
