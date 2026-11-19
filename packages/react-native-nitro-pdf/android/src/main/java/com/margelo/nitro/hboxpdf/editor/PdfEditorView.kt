package com.margelo.nitro.hboxpdf.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import kotlin.math.roundToInt
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * The whole PDF viewer and editor, painted with Android views.
 *
 * Chrome (app bar, tool strip, contextual options, bottom sheets, comment
 * threads, signature pad) is native; React only supplies the document path and
 * receives edits as JSON. Keeping the chrome native is what removes the
 * "tap once to arm, tap again to act" feel of the previous React chrome: a tool
 * is active as soon as it is tapped, and the page gesture immediately does the
 * thing the user selected.
 */
internal class PdfEditorView(context: Context) : FrameLayout(context) {
  var onBack: (() -> Unit)? = null
  var onShare: (() -> Unit)? = null
  var onReadAloud: ((Int) -> Unit)? = null
  var onMarksChanged: ((String) -> Unit)? = null
  var onPageChanged: ((Int) -> Unit)? = null
  var onDocumentReady: ((Int) -> Unit)? = null

  private val surface = PdfPagesSurface(context)
  private val column = LinearLayout(context)
  private val topBar = LinearLayout(context)
  private val toolStrip = LinearLayout(context)
  private val optionsBar = LinearLayout(context)
  private val sheet = PdfSheet(context).apply {
    onDismiss = { editingMark = null }
  }
  private val hint = context.label("", 12.5f, Color.WHITE, Typeface.BOLD)

  private val backButton = PdfIconButton(context)
  private val undoButton = PdfIconButton(context)
  private val redoButton = PdfIconButton(context)
  private val annotateButton = PdfIconButton(context)
  private val searchButton = PdfIconButton(context)
  private val shareButton = PdfIconButton(context)
  private val moreButton = PdfIconButton(context)

  override fun requestLayout() {
    super.requestLayout()
    if (inRelayout || relayoutQueued) return
    relayoutQueued = true
    post {
      relayoutQueued = false
      relayout()
    }
  }

  /**
   * Fabric drives this view's bounds from Yoga and drops `requestLayout()` from
   * anything below the React root, so chrome shown after the first layout pass
   * (tool options, comment and fill&sign sheets, the hint pill) would otherwise
   * never be measured. Re-measuring at the current bounds paints it immediately.
   */
  private fun relayout() {
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

  private var relayoutQueued = false
  private var inRelayout = false

  private val editTool = PdfToolButton(context)
  private val commentTool = PdfToolButton(context)
  private val highlightTool = PdfToolButton(context)
  private val drawTool = PdfToolButton(context)
  private val signTool = PdfToolButton(context)
  private val moreTools = PdfToolButton(context)

  private var marks: MutableList<PdfMark> = mutableListOf()
  private var tool: PdfTool = PdfTool.NONE
  private var page = 1
  private var pageCount = 0
  private var highlightColor = PdfUi.HIGHLIGHTS[0]
  private var inkColor = PdfUi.INKS[0]
  /** "highlight" (band) or "underline" (line under the text). */
  private var highlightStyle = "highlight"
  private var highlightOpacity = 0.4f
  private var markupExpanded = false
  /** Mark the open sheet is restyling, or null while it is setting new marks. */
  private var editingMark: String? = null
  private var strokeStep = 1
  private var inkOpacity = 1f
  private var drawExpanded = false
  private var pendingPoint: Pair<Int, Pair<Float, Float>>? = null
  private var initialAnnotationsApplied = false
  private var lastBackToken = 0
  /** Marks are also kept on disk, so a remount or a killed app loses nothing. */
  private val markStore = PdfMarkStore(context)
  private var restoredMarks = false
  private var currentDocumentPath: String? = null

  private val signatures = mutableListOf<PdfMark>()
  private val hintHide = Runnable { fadeHint() }

  init {
    setBackgroundColor(PdfUi.CANVAS)
    clipChildren = false

    column.orientation = LinearLayout.VERTICAL
    addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

    buildTopBar()
    column.addView(topBar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dpi(56f)))
    column.addView(surface, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    buildToolStrip()
    column.addView(toolStrip, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dpi(66f)))

    // The contextual tool options float above the strip instead of pushing the
    // page area: resizing the surface on every tool change would drop the
    // rendered pages and flicker.
    buildOptionsBar()
    optionsBar.elevation = dpf(6f)
    addView(optionsBar, LayoutParams(LayoutParams.MATCH_PARENT, dpi(54f), Gravity.BOTTOM).apply {
      bottomMargin = dpi(66f)
    })

    addView(sheet, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    sheet.onDismiss = { refreshChrome() }

    hint.setBackground(rounded(0xF2101418.toInt(), 16f, context))
    hint.setPadding(dpi(16f), dpi(9f), dpi(16f), dpi(9f))
    hint.alpha = 0f
    hint.elevation = dpf(8f)
    addView(hint, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
      bottomMargin = dpi(84f)
    })

    surface.onPageChanged = { changed, total ->
      page = changed
      pageCount = total
      onPageChanged?.invoke(changed)
    }
    // Tapping a mark with no tool armed opens its sheet on that mark, so a
    // wrong colour or weight is fixed instead of deleted and redrawn.
    surface.onSelectionChanged = { mark -> handleMarkSelected(mark) }
    surface.onMarksChanged = {
      marks = surface.marks
      emitMarks()
      refreshChrome()
    }
    surface.onCommentAt = { pageNumber, nx, ny -> showComposer(pageNumber, nx, ny) }
    surface.onNoteTapped = { note -> showThread(note.id) }
    surface.onHint = { message -> showHint(message) }
    surface.onDocumentReady = { total ->
      pageCount = total
      onDocumentReady?.invoke(total)
    }
    surface.onOpenFailed = { showHint("This document could not be opened") }
    sheet.visibility = GONE
    refreshChrome()
  }

  // ---- public API ---------------------------------------------------------

  fun setDocumentPath(path: String?) {
    val target = path?.trim().orEmpty()
    if (target.isEmpty()) return
    if (target != currentDocumentPath) {
      // Another document: drop the previous one's marks before restoring.
      currentDocumentPath = target
      marks = mutableListOf()
      surface.marks = marks
      restoredMarks = false
      initialAnnotationsApplied = false
    }
    surface.open(target, 1)
    restoreMarkup(target)
    refreshChrome()
  }

  /**
   * Brings back the marks of an earlier session. The sidecar outranks the
   * `annotations` prop: the prop is what React last rendered, the sidecar is
   * what the user actually did.
   */
  private fun restoreMarkup(path: String) {
    val saved = markStore.open(path).toMutableList()
    if (saved.isEmpty()) return
    marks = saved
    surface.marks = saved
    restoredMarks = true
    emitMarks()
    refreshChrome()
  }

  fun setInitialAnnotations(json: String?) {
    if (initialAnnotationsApplied || restoredMarks) return
    val decoded = PdfMarkCodec.decode(json)
    initialAnnotationsApplied = true
    if (decoded.isEmpty()) return
    marks = decoded
    surface.marks = decoded
    refreshChrome()
    emitMarks()
  }

  fun setBackToken(token: Int) {
    if (token <= lastBackToken) return
    lastBackToken = token
    if (sheet.isOpen()) {
      sheet.close()
      return
    }
    if (tool != PdfTool.NONE) {
      setTool(PdfTool.NONE)
      return
    }
    if (surface.selectedMark() != null) {
      surface.select(null)
      return
    }
    onBack?.invoke()
  }

  fun release() {
    hint.removeCallbacks(hintHide)
    markStore.detach()
    surface.release()
  }

  // ---- chrome -------------------------------------------------------------

  private fun buildTopBar() {
    topBar.orientation = LinearLayout.HORIZONTAL
    topBar.gravity = Gravity.CENTER_VERTICAL
    topBar.setBackgroundColor(PdfUi.SURFACE)
    topBar.elevation = dpf(3f)
    topBar.setPadding(dpi(4f), 0, dpi(4f), 0)

    bind(backButton, PdfIcon.BACK, "Back") { if (tool == PdfTool.NONE) onBack?.invoke() else setTool(PdfTool.NONE) }
    bind(undoButton, PdfIcon.UNDO, "Undo") {
      surface.undoMark()
      showHint("Undid the last change")
    }
    bind(redoButton, PdfIcon.UNDO, "Redo") {
      surface.redo()
      showHint("Redid the change")
    }
    // The redo icon is the undo icon mirrored, which keeps the pair readable.
    redoButton.iconView.scaleX = -1f
    bind(annotateButton, PdfIcon.ANNOTATE, "Annotate") { showEditSheet() }
    bind(searchButton, PdfIcon.SEARCH, "Search") { showSearchSheet() }
    bind(shareButton, PdfIcon.SHARE, "Share") { onShare?.invoke() }
    bind(moreButton, PdfIcon.MORE, "More") { showMoreSheet() }

    topBar.addView(backButton)
    topBar.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
    topBar.addView(undoButton)
    topBar.addView(redoButton)
    topBar.addView(annotateButton)
    topBar.addView(searchButton)
    topBar.addView(shareButton)
    topBar.addView(moreButton)
  }

  private fun bind(button: PdfIconButton, icon: PdfIcon, label: String, onClick: () -> Unit) {
    button.iconView.icon = icon
    button.contentDescription = label
    button.setOnClickListener { onClick() }
  }

  private fun buildOptionsBar() {
    optionsBar.orientation = LinearLayout.HORIZONTAL
    optionsBar.gravity = Gravity.CENTER_VERTICAL
    optionsBar.setBackgroundColor(PdfUi.SURFACE)
    optionsBar.setPadding(dpi(14f), 0, dpi(8f), 0)
    optionsBar.visibility = GONE
  }

  private fun buildToolStrip() {
    toolStrip.orientation = LinearLayout.HORIZONTAL
    toolStrip.setBackgroundColor(PdfUi.SURFACE)
    toolStrip.setPadding(0, dpi(2f), 0, dpi(4f))
    toolStrip.elevation = dpf(8f)

    editTool.bind(PdfIcon.EDIT, "Edit PDF")
    commentTool.bind(PdfIcon.COMMENT, "Comment")
    highlightTool.bind(PdfIcon.HIGHLIGHT, "Highlight")
    drawTool.bind(PdfIcon.DRAW, "Draw")
    signTool.bind(PdfIcon.SIGN, "Fill & Sign")
    moreTools.bind(PdfIcon.TOOLS, "More tools")

    editTool.setOnClickListener { showEditSheet() }
    commentTool.setOnClickListener { onCommentTool() }
    highlightTool.setOnClickListener { showHighlightSheet() }
    drawTool.setOnClickListener { showDrawSheet() }
    signTool.setOnClickListener { showSignSheet() }
    moreTools.setOnClickListener { showMoreSheet() }

    listOf(editTool, commentTool, highlightTool, drawTool, signTool, moreTools).forEach { button ->
      toolStrip.addView(button, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }
  }

  private fun refreshChrome() {
    backButton.iconView.icon = if (tool == PdfTool.NONE) PdfIcon.BACK else PdfIcon.CHECK
    val canUndo = surface.canUndo()
    val canRedo = surface.canRedo()
    undoButton.iconView.color = if (canUndo) PdfUi.INK else 0xFFC8CCD3.toInt()
    undoButton.isEnabled = canUndo
    undoButton.alpha = if (canUndo) 1f else 0.4f
    redoButton.iconView.color = if (canRedo) PdfUi.INK else 0xFFC8CCD3.toInt()
    redoButton.isEnabled = canRedo
    redoButton.alpha = if (canRedo) 1f else 0.4f

    highlightTool.setState(tool == PdfTool.HIGHLIGHT)
    drawTool.setState(tool == PdfTool.DRAW)
    commentTool.setState(tool == PdfTool.COMMENT)
    signTool.setState(tool == PdfTool.SIGN)
    editTool.setState(false)
    moreTools.setState(false)

    if (tool == PdfTool.SIGN) {
      fillStampOptions()
      if (optionsBar.visibility != VISIBLE) {
        optionsBar.visibility = VISIBLE
        optionsBar.alpha = 0f
        optionsBar.animate().alpha(1f).setDuration(160L).start()
      }
    } else {
      optionsBar.visibility = GONE
    }
    updateHintMargin()
  }

  private fun fillStampOptions() {
    optionsBar.removeAllViews()
    val title = context.label("FILL & SIGN", 9.5f, PdfUi.MUTED, Typeface.BOLD)
    title.letterSpacing = 0.1f
    optionsBar.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
      rightMargin = dpi(12f)
    })

    val stamps = listOf(
      "check" to "\u2713",
      "cross" to "\u2715",
      "dot" to "\u25CF",
      "box" to "\u25A1",
      "line" to "\u2014",
    )
    stamps.forEach { (glyph, glyphText) ->
      val button = context.label(glyphText, 20f, PdfUi.INK, Typeface.NORMAL)
      button.gravity = Gravity.CENTER
      button.isClickable = true
      button.background = rounded(0x00000000, 12f, context)
      button.setOnClickListener { dropStamp(glyph) }
      optionsBar.addView(button, LinearLayout.LayoutParams(dpi(46f), dpi(46f)))
    }

    optionsBar.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
    val add = PdfIconButton(context)
    add.iconView.icon = PdfIcon.SIGN
    add.setOnClickListener { showSignSheet() }
    optionsBar.addView(add, LinearLayout.LayoutParams(dpi(42f), dpi(42f)))
  }

  private fun applyToolColors() {
    surface.highlightColor = highlightColor
    surface.inkColor = inkColor
    surface.strokeStep = strokeStep
    surface.inkOpacity = inkOpacity
    surface.highlightStyle = highlightStyle
    surface.highlightOpacity = highlightOpacity
    syncEditingMark()
  }

  /**
   * Restyling: the open sheet writes its choice straight onto the mark. Values
   * are compared first, so loading a mark's own style back into the sheet does
   * not emit a change for a mark that already looks like that.
   */
  private fun syncEditingMark() {
    val id = editingMark ?: return
    val mark = surface.markById(id) ?: return
    val color: Int
    val glyph: String?
    val width: Float?
    when (mark.kind) {
      MarkKind.HIGHLIGHT -> {
        color = markupColorValue()
        glyph = highlightStyle
        width = null
      }
      MarkKind.INK -> {
        color = inkColorValue()
        glyph = null
        width = inkWidthValue()
      }
      else -> return
    }
    if (mark.color == color && (glyph == null || mark.glyph == glyph) && (width == null || mark.width == width)) return
    surface.restyleMark(id, color, glyph, width)
  }

  /** Selected colour with the opacity folded into its alpha. */
  private fun markupColorValue(): Int {
    val rgb = highlightColor and 0x00FFFFFF
    val alpha = when (highlightStyle) {
      "underline", "strikeout" -> 0xFF
      else -> (highlightOpacity.coerceIn(0.05f, 1f) * 255f).toInt()
    }
    return rgb or (alpha shl 24)
  }

  private fun inkColorValue(): Int {
    val alpha = (inkOpacity.coerceIn(0.05f, 1f) * 255f).toInt()
    return (inkColor and 0x00FFFFFF) or (alpha shl 24)
  }

  private fun inkWidthValue(): Float =
    0.0018f + strokeStep.coerceIn(0, STROKE_STEPS - 1) * 0.0017f

  // ---- tools --------------------------------------------------------------

  private fun setTool(next: PdfTool) {
    tool = next
    surface.tool = next
    applyToolColors()
    if (next != PdfTool.SIGN) surface.pendingSignature = null
    refreshChrome()
  }

  private fun toggleTool(next: PdfTool) {
    if (tool == next) {
      setTool(PdfTool.NONE)
      return
    }
    setTool(next)
    when (next) {
      PdfTool.HIGHLIGHT -> showHint("Drag across the page to highlight")
      PdfTool.DRAW -> showHint("Draw on the page with one finger")
      PdfTool.COMMENT -> showHint("Tap the page to drop a comment")
      else -> Unit
    }
  }

  private fun onCommentTool() {
    val notes = surface.notes()
    if (notes.isEmpty()) {
      showComposer(page, 0.5f, 0.45f)
      return
    }
    showThread(notes.last().id)
  }

  // ---- comment sheets -----------------------------------------------------

  private fun showComposer(targetPage: Int, nx: Float, ny: Float) {
    pendingPoint = targetPage to (nx to ny)
    setTool(PdfTool.NONE)
    sheet.setBody { body ->
      body.addView(sheetTitle("New comment"))
      val field = composerField("Add a comment")
      body.addView(field, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = dpi(10f)
      })
      val post = primaryButton("Post comment") {
        val text = field.text.toString().trim()
        if (text.isEmpty()) {
          showHint("Write something first")
        } else {
          val (pageNumber, point) = pendingPoint ?: (page to (0.5f to 0.5f))
          val mark = PdfMark(
            id = UUID.randomUUID().toString(),
            kind = MarkKind.NOTE,
            page = pageNumber,
            rect = RectF(point.first, point.second, point.first, point.second),
            color = PdfUi.AMBER,
            text = text,
          )
          surface.addMark(mark)
          pendingPoint = null
          hideKeyboard(field)
          showThread(mark.id)
        }
      }
      body.addView(post, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dpi(48f)).apply {
        topMargin = dpi(8f)
      })
      body.addView(context.label("Tap Post and the comment lands on the page, pinned in place.", 11.5f, PdfUi.MUTED).apply {
        setPadding(0, dpi(10f), 0, 0)
      })
      focus(field)
    }
    sheet.open()
  }

  private fun showThread(noteId: String?) {
    val notes = surface.notes()
    if (notes.isEmpty()) {
      showComposer(page, 0.5f, 0.45f)
      return
    }
    var index = notes.indexOfFirst { it.id == noteId }
    if (index < 0) index = notes.size - 1
    val note = notes[index]
    surface.selectNote(note.id)
    // Opening a comment from the list jumps to the page it belongs to.
    if (note.page != page) surface.scrollToPage(note.page, true)

    sheet.setBody { body ->
      val pager = LinearLayout(context)
      pager.orientation = LinearLayout.HORIZONTAL
      pager.gravity = Gravity.CENTER_VERTICAL
      val previous = PdfIconButton(context)
      previous.iconView.icon = PdfIcon.CHEVRON_LEFT
      previous.isEnabled = index > 0
      previous.alpha = if (index > 0) 1f else 0.3f
      previous.setOnClickListener { showThread(notes[(index - 1).coerceAtLeast(0)].id) }
      val label = context.label("${index + 1} of ${notes.size}", 13f, PdfUi.INK_SOFT, Typeface.NORMAL)
      label.gravity = Gravity.CENTER
      val next = PdfIconButton(context)
      next.iconView.icon = PdfIcon.CHEVRON_RIGHT
      next.isEnabled = index < notes.size - 1
      next.alpha = if (index < notes.size - 1) 1f else 0.3f
      next.setOnClickListener { showThread(notes[(index + 1).coerceAtMost(notes.size - 1)].id) }
      pager.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
      pager.addView(previous, LinearLayout.LayoutParams(dpi(38f), dpi(38f)))
      pager.addView(label, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
        leftMargin = dpi(6f)
        rightMargin = dpi(6f)
      })
      pager.addView(next, LinearLayout.LayoutParams(dpi(38f), dpi(38f)))
      pager.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
      body.addView(pager, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

      val header = LinearLayout(context)
      header.orientation = LinearLayout.HORIZONTAL
      header.gravity = Gravity.CENTER_VERTICAL

      val avatar = TextView(context)
      avatar.text = "G"
      avatar.setTextColor(Color.WHITE)
      avatar.textSize = 18f
      avatar.gravity = Gravity.CENTER
      avatar.typeface = Typeface.create("sans-serif", Typeface.BOLD)
      avatar.background = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
        colors = intArrayOf(0xFFB5A6F6.toInt(), 0xFF8F7BEF.toInt())
        gradientType = android.graphics.drawable.GradientDrawable.LINEAR_GRADIENT
        orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
      }
      header.addView(avatar, LinearLayout.LayoutParams(dpi(40f), dpi(40f)))

      val identity = LinearLayout(context)
      identity.orientation = LinearLayout.VERTICAL
      identity.setPadding(dpi(12f), 0, 0, 0)
      identity.addView(context.label("Guest", 16f, PdfUi.INK, Typeface.BOLD))
      identity.addView(context.label("Today \u00B7 page ${note.page}", 12.5f, PdfUi.MUTED))
      header.addView(identity, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

      val dot = View(context)
      dot.background = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
        setColor(note.color)
      }
      header.addView(dot, LinearLayout.LayoutParams(dpi(11f), dpi(11f)).apply { rightMargin = dpi(8f) })

      val menu = PdfIconButton(context)
      menu.iconView.icon = PdfIcon.MORE
      menu.background = rounded(0xFFF2F3F5.toInt(), 10f, context)
      menu.setOnClickListener { showNoteMenu(menu, note) }
      header.addView(menu, LinearLayout.LayoutParams(dpi(38f), dpi(38f)))
      body.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = dpi(12f)
      })

      val text = context.label(note.text, 15.5f, PdfUi.INK).apply {
        setPadding(dpi(2f), dpi(14f), dpi(2f), dpi(6f))
      }
      body.addView(text, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

      val replyRow = LinearLayout(context)
      replyRow.orientation = LinearLayout.HORIZONTAL
      replyRow.gravity = Gravity.CENTER_VERTICAL
      replyRow.setPadding(dpi(12f), 0, dpi(6f), 0)
      replyRow.background = rounded(0xFFF7F8FA.toInt(), 14f, context, 0xFFDDE0E5.toInt(), 1f)
      val reply = EditText(context)
      reply.hint = "Reply or use @ to invite others"
      reply.textSize = 14f
      reply.setTextColor(PdfUi.INK)
      reply.setHintTextColor(PdfUi.MUTED)
      reply.background = null
      reply.maxLines = 3
      reply.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
      replyRow.addView(reply, LinearLayout.LayoutParams(0, dpi(46f), 1f))
      val send = PdfIconButton(context)
      send.iconView.icon = PdfIcon.SEND
      send.iconView.color = PdfUi.ACCENT
      send.setOnClickListener {
        val answer = reply.text.toString().trim()
        if (answer.isEmpty()) return@setOnClickListener
        surface.updateMark(note.id) { mark -> mark.text = mark.text + "\n" + answer }
        hideKeyboard(reply)
        showThread(note.id)
      }
      replyRow.addView(send, LinearLayout.LayoutParams(dpi(40f), dpi(40f)))
      body.addView(replyRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = dpi(14f)
      })

      val add = primaryButton("Add a comment") {
        showComposer(page, 0.5f, 0.6f)
      }
      body.addView(add, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dpi(48f)).apply {
        topMargin = dpi(14f)
      })
    }
    sheet.open()
  }

  private fun showNoteMenu(anchor: View, note: PdfMark) {
    val panel = LinearLayout(context)
    panel.orientation = LinearLayout.VERTICAL
    panel.setPadding(dpi(6f), dpi(6f), dpi(6f), dpi(6f))
    panel.background = rounded(PdfUi.SURFACE, 16f, context)
    panel.elevation = dpf(14f)

    val popup = PopupWindow(panel, dpi(210f), LayoutParams.WRAP_CONTENT, true)
    popup.isOutsideTouchable = true
    popup.elevation = dpf(12f)

    fun row(icon: PdfIcon, title: String, danger: Boolean = false, action: () -> Unit) {
      val item = LinearLayout(context)
      item.orientation = LinearLayout.HORIZONTAL
      item.gravity = Gravity.CENTER_VERTICAL
      item.setPadding(dpi(10f), 0, dpi(10f), 0)
      item.isClickable = true
      item.background = rounded(0x00000000, 10f, context)
      val tint = if (danger) PdfUi.DANGER else PdfUi.INK
      val iconView = PdfIconView(context)
      iconView.icon = icon
      iconView.color = tint
      iconView.weight = 1.4f
      item.addView(iconView, LinearLayout.LayoutParams(dpi(20f), dpi(20f)))
      item.addView(context.label(title, 14.5f, tint), LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
        leftMargin = dpi(12f)
      })
      item.setOnClickListener {
        popup.dismiss()
        action()
      }
      panel.addView(item, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dpi(46f)))
    }

    row(PdfIcon.COPY, "Copy text") {
      val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
      clipboard?.setPrimaryClip(ClipData.newPlainText("Comment", note.text))
      showHint("Comment copied")
    }
    row(PdfIcon.PENCIL, "Edit comment") { editNote(note) }
    row(PdfIcon.TRASH, "Delete", danger = true) {
      surface.removeMark(note.id)
      sheet.close()
      showHint("Comment deleted — undo to restore")
    }

    popup.showAsDropDown(anchor, -dpi(160f), dpi(4f))
  }

  private fun editNote(note: PdfMark) {
    sheet.setBody { body ->
      body.addView(sheetTitle("Edit comment"))
      val field = composerField("Comment")
      field.setText(note.text)
      field.setSelection(note.text.length)
      body.addView(field, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = dpi(10f)
      })
      val save = primaryButton("Save") {
        val text = field.text.toString().trim()
        if (text.isNotEmpty()) {
          surface.updateMark(note.id) { mark -> mark.text = text }
        }
        hideKeyboard(field)
        showThread(note.id)
      }
      body.addView(save, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dpi(48f)).apply {
        topMargin = dpi(8f)
      })
      focus(field)
    }
    sheet.open()
  }

  // ---- fill & sign --------------------------------------------------------

  private fun showSignSheet() {
    // A menu, not a pad: Fill & Sign lists everything that can be placed and
    // only the chosen item arms the tool and opens its own editor.
    sheet.setBody { body ->
      body.addView(sheetTitle("Fill & Sign"))
      body.addView(actionRow(PdfIcon.SIGN, "Signature", "Draw once") {
        sheet.close()
        setTool(PdfTool.SIGN)
        showPad(initials = false)
      })
      body.addView(actionRow(PdfIcon.PENCIL, "Initials", "Short form") {
        sheet.close()
        setTool(PdfTool.SIGN)
        showPad(initials = true)
      })
      body.addView(actionRow(PdfIcon.ANNOTATE, "Date", todayLabel()) {
        sheet.close()
        dropDate()
      })
      body.addView(actionRow(PdfIcon.EDIT, "Text", "Type anything") {
        sheet.close()
        showTextComposer()
      })
      body.addView(actionRow(PdfIcon.CHECK, "Stamps", "Tick, cross, box") {
        sheet.close()
        setTool(PdfTool.SIGN)
        showHint("Pick a stamp from the bar, then tap the page")
      })

      if (signatures.isNotEmpty()) {
        body.addView(context.label("Saved", 11f, PdfUi.MUTED, Typeface.BOLD).apply {
          letterSpacing = 0.08f
          setPadding(0, dpi(16f), 0, dpi(8f))
        })
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        signatures.forEach { saved ->
          val chip = context.label(if (saved.glyph == "initials") "Initials" else "Signature", 13f, PdfUi.ACCENT, Typeface.BOLD)
          chip.gravity = Gravity.CENTER
          chip.setPadding(dpi(14f), dpi(10f), dpi(14f), dpi(10f))
          chip.background = rounded(PdfUi.ACCENT_SOFT, 12f, context)
          chip.setOnClickListener {
            placeAtCenter(saved, page)
            sheet.close()
            showHint("Drag the signature into place")
          }
          row.addView(chip, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dpi(8f)
          })
        }
        body.addView(row)
      }
      body.addView(context.label(
        "Anything you add can be dragged into place, and the bin removes it.",
        11.5f,
        PdfUi.MUTED,
      ).apply { setPadding(0, dpi(12f), 0, 0) })
    }
    sheet.open()
  }

  private fun todayLabel(): String =
    java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date())

  private fun dropDate() {
    val text = todayLabel()
    placeText(text, (0.14f + text.length * 0.014f).coerceIn(0.24f, 0.55f))
    showHint("Drag the date where it belongs")
  }

  private fun showTextComposer() {
    sheet.setBody { body ->
      body.addView(sheetTitle("Add text"))
      val field = composerField("Type the text to place")
      field.minLines = 2
      body.addView(field, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = dpi(10f)
      })
      val place = primaryButton("Place on page") {
        val text = field.text.toString().trim()
        if (text.isEmpty()) {
          showHint("Type something first")
        } else {
          hideKeyboard(field)
          placeText(text, (0.10f + text.length * 0.011f).coerceIn(0.2f, 0.72f))
          showHint("Drag the text where it belongs")
        }
      }
      body.addView(place, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dpi(48f)).apply {
        topMargin = dpi(8f)
      })
      focus(field)
    }
    sheet.open()
  }

  private fun placeText(text: String, width: Float) {
    val mark = PdfMark(
      id = UUID.randomUUID().toString(),
      kind = MarkKind.STAMP,
      page = page,
      color = PdfUi.INK,
      rect = RectF(0f, 0f, width, 0.048f),
      text = text,
      glyph = "text",
    )
    placeAtCenter(mark, page)
  }

  private fun signRow(title: String, action: () -> Unit): View {
    val row = LinearLayout(context)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    row.setPadding(dpi(16f), 0, dpi(12f), 0)
    row.isClickable = true
    row.background = rounded(0x00000000, 14f, context, 0xFFDDE0E5.toInt(), 1f)
    row.addView(context.label(title, 15f, PdfUi.INK), LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    val plus = PdfIconButton(context)
    plus.iconView.icon = PdfIcon.PLUS
    plus.iconView.color = PdfUi.INK
    row.addView(plus, LinearLayout.LayoutParams(dpi(40f), dpi(40f)))
    row.setOnClickListener { action() }
    return row.apply {
      layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dpi(58f)).apply { topMargin = dpi(10f) }
    }
  }

  private fun showPad(initials: Boolean) {
    sheet.setBody { body ->
      body.addView(sheetTitle(if (initials) "Add initials" else "Add signature"))
      val pad = SignaturePadView(context)
      body.addView(pad, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dpi(180f)).apply {
        topMargin = dpi(14f)
      })
      val actions = LinearLayout(context)
      actions.orientation = LinearLayout.HORIZONTAL
      actions.gravity = Gravity.CENTER_VERTICAL
      val clear = context.label("Clear", 14f, PdfUi.INK_SOFT, Typeface.BOLD)
      clear.gravity = Gravity.CENTER
      clear.isClickable = true
      clear.setPadding(dpi(14f), dpi(10f), dpi(14f), dpi(10f))
      clear.background = rounded(0xFFF2F3F5.toInt(), 12f, context)
      clear.setOnClickListener { pad.clear() }
      actions.addView(clear, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
      actions.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
      val use = context.label("Use", 14f, Color.WHITE, Typeface.BOLD)
      use.gravity = Gravity.CENTER
      use.isClickable = true
      use.setPadding(dpi(26f), dpi(11f), dpi(26f), dpi(11f))
      use.background = rounded(PdfUi.ACCENT, 12f, context)
      use.setOnClickListener {
        val strokes = pad.normalizedStrokes()
        if (strokes.isEmpty()) {
          showHint("Sign in the box first")
          return@setOnClickListener
        }
        val mark = PdfMark(
          id = UUID.randomUUID().toString(),
          kind = MarkKind.SIGNATURE,
          color = PdfUi.INK,
          width = 0.0032f,
          points = flatten(strokes),
          glyph = if (initials) "initials" else "signature",
          rect = if (initials) RectF(0f, 0f, 0.16f, 0.08f) else RectF(0f, 0f, 0.34f, 0.15f),
        )
        signatures.add(mark)
        placeAtCenter(mark, page)
        sheet.close()
        showHint("Drag the ${if (initials) "initials" else "signature"} into place")
      }
      actions.addView(use, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
      body.addView(actions, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = dpi(12f)
      })
    }
    sheet.open()
  }

  private fun dropStamp(glyph: String) {
    val mark = PdfMark(
      id = UUID.randomUUID().toString(),
      kind = MarkKind.STAMP,
      color = if (glyph == "cross") PdfUi.DANGER else PdfUi.INK,
      glyph = glyph,
      rect = RectF(0f, 0f, if (glyph == "line") 0.16f else 0.07f, 0.05f),
    )
    placeAtCenter(mark, page)
    showHint("Drag to position, or tap the bin to remove")
  }

  private fun placeAtCenter(template: PdfMark, targetPage: Int) {
    val mark = template.copy()
    mark.id = UUID.randomUUID().toString()
    mark.page = targetPage
    val width = mark.rect.width()
    val height = mark.rect.height()
    mark.rect.set(0.5f - width / 2f, 0.45f - height / 2f, 0.5f + width / 2f, 0.45f + height / 2f)
    surface.addMark(mark)
    // Leaving the template armed means a second tap drops another copy, which
    // is how stamps behave in Acrobat.
    surface.pendingSignature = mark.copy()
    setTool(PdfTool.SIGN)
    surface.select(mark)
  }

  private fun flatten(strokes: List<FloatArray>): FloatArray {
    var size = 0
    strokes.forEach { size += it.size }
    val result = FloatArray(size)
    var offset = 0
    strokes.forEach { stroke ->
      stroke.copyInto(result, offset)
      offset += stroke.size
    }
    return result
  }

  // ---- markup sheets ------------------------------------------------------

  private fun showHighlightSheet(editingId: String? = null) {
    editingMark = editingId
    if (editingId == null) {
      setTool(PdfTool.HIGHLIGHT)
      surface.prepareMarkup()
      showHint(markupHint())
    } else {
      // One snapshot for the whole restyle, taken at the first change.
      surface.beginRestyle()
    }
    markupExpanded = false
    sheet.setBody { body -> buildMarkupSheet(body) }
    // A tool tray, not a dialog: the page above it stays interactive.
    sheet.open(nonModal = true)
  }

  private fun handleMarkSelected(mark: PdfMark?) {
    if (mark == null) return
    // With a markup tool armed the selection is the mark being dragged, not a
    // request to restyle.
    if (tool != PdfTool.NONE) return
    surface.clearSelection()
    when (mark.kind) {
      MarkKind.HIGHLIGHT -> {
        loadMarkupStyle(mark)
        showHighlightSheet(mark.id)
      }
      MarkKind.INK -> {
        loadInkStyle(mark)
        showDrawSheet(mark.id)
      }
      else -> Unit
    }
  }

  private fun loadMarkupStyle(mark: PdfMark) {
    highlightColor = mark.color or 0xFF000000.toInt()
    if (mark.glyph.isNotBlank()) highlightStyle = mark.glyph
    highlightOpacity = if (highlightStyle == "highlight") {
      (((mark.color ushr 24) and 0xFF) / 255f).coerceIn(0.05f, 1f)
    } else {
      1f
    }
    applyToolColors()
  }

  private fun loadInkStyle(mark: PdfMark) {
    inkColor = mark.color or 0xFF000000.toInt()
    inkOpacity = (((mark.color ushr 24) and 0xFF) / 255f).coerceIn(0.05f, 1f)
    strokeStep = ((mark.width - 0.0018f) / 0.0017f).roundToInt().coerceIn(0, STROKE_STEPS - 1)
    applyToolColors()
  }

  private fun rebuildMarkupSheet() {
    sheet.setBody { body -> buildMarkupSheet(body) }
  }

  private fun showDrawSheet(editingId: String? = null) {
    editingMark = editingId
    if (editingId == null) {
      setTool(PdfTool.DRAW)
    } else {
      surface.beginRestyle()
    }
    drawExpanded = false
    sheet.setBody { body -> buildDrawSheet(body) }
    sheet.open(nonModal = true)
  }

  private fun rebuildDrawSheet() {
    sheet.setBody { body -> buildDrawSheet(body) }
  }

  /**
   * Highlighter: three markup styles and the current colour, expanding in place
   * to the palette and opacity slider.
   */
  private fun buildMarkupSheet(body: LinearLayout) {
    body.addView(sheetTitle("Highlight"))

    val row = LinearLayout(context)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    row.addView(markupMode("highlight"), LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    row.addView(markupMode("underline"), LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
      leftMargin = dpi(8f)
    })
    row.addView(markupMode("strikeout"), LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
      leftMargin = dpi(8f)
    })
    row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
    row.addView(colorDot(highlightColor) {
      markupExpanded = !markupExpanded
      rebuildMarkupSheet()
    }, LinearLayout.LayoutParams(dpi(44f), dpi(44f)))
    body.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
      topMargin = dpi(6f)
    })

    if (!markupExpanded) {
      body.addView(context.label(markupHint(), 12.5f, PdfUi.MUTED).apply {
        setPadding(0, dpi(14f), 0, dpi(4f))
      })
      return
    }

    colorGrid(body, "Highlight color", highlightColor) { color ->
      highlightColor = color
      applyToolColors()
      rebuildMarkupSheet()
    }
    opacitySection(body, highlightColor, highlightOpacity) { value ->
      highlightOpacity = value
      applyToolColors()
    }
  }

  /**
   * Pen: line thickness presets and the current colour, expanding in place to the
   * same palette and opacity slider the highlighter uses.
   */
  private fun buildDrawSheet(body: LinearLayout) {
    body.addView(sheetTitle("Draw"))

    val head = LinearLayout(context)
    head.orientation = LinearLayout.HORIZONTAL
    head.gravity = Gravity.CENTER_VERTICAL
    head.addView(context.label("Line thickness", 12.5f, PdfUi.INK_SOFT, Typeface.BOLD), LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    head.addView(colorDot(inkColor) {
      drawExpanded = !drawExpanded
      rebuildDrawSheet()
    }, LinearLayout.LayoutParams(dpi(44f), dpi(44f)))
    body.addView(head, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
      topMargin = dpi(8f)
    })

    val row = LinearLayout(context)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    for (step in 0 until STROKE_STEPS) {
      row.addView(thicknessChip(step), LinearLayout.LayoutParams(0, dpi(46f), 1f))
    }
    body.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
      topMargin = dpi(4f)
    })

    if (!drawExpanded) {
      body.addView(context.label("Draw on the page with one finger.", 12.5f, PdfUi.MUTED).apply {
        setPadding(0, dpi(16f), 0, dpi(4f))
      })
      return
    }

    colorGrid(body, "Color", inkColor) { color ->
      inkColor = color
      applyToolColors()
      rebuildDrawSheet()
    }
    opacitySection(body, inkColor, inkOpacity) { value ->
      inkOpacity = value
      applyToolColors()
    }
  }

  /** Round swatch that opens the palette; shared by both sheets. */
  private fun colorDot(color: Int, onClick: () -> Unit): View {
    val frame = FrameLayout(context)
    frame.isClickable = true
    frame.contentDescription = "Colour"
    val dot = PdfSwatch(context)
    // The swatch is a 40dp glyph inside a 44dp target: it must not take the
    // touch itself, or the frame's click never fires and the palette is dead.
    dot.isClickable = false
    dot.color = color
    frame.addView(dot, FrameLayout.LayoutParams(dpi(40f), dpi(40f), Gravity.CENTER))
    frame.setOnClickListener { onClick() }
    return frame
  }

  private fun colorGrid(body: LinearLayout, title: String, selected: Int, onPick: (Int) -> Unit) {
    body.addView(context.label(title, 13f, PdfUi.INK_SOFT, Typeface.BOLD).apply {
      setPadding(0, dpi(18f), 0, dpi(8f))
    })
    val grid = LinearLayout(context)
    grid.orientation = LinearLayout.VERTICAL
    MARKUP_COLORS.toList().chunked(6).forEach { chunk ->
      val line = LinearLayout(context)
      line.orientation = LinearLayout.HORIZONTAL
      chunk.forEach { color ->
        val swatch = PdfSwatch(context)
        swatch.color = color
        swatch.square = true
        swatch.checked = (color and 0x00FFFFFF) == (selected and 0x00FFFFFF)
        swatch.contentDescription = PdfColorName.of(color)
        swatch.setOnClickListener { onPick(color) }
        // 44 dp keeps every swatch a comfortable touch target.
        line.addView(swatch, LinearLayout.LayoutParams(0, dpi(44f), 1f))
      }
      grid.addView(line, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    body.addView(grid, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
      topMargin = dpi(2f)
    })
  }

  private fun opacitySection(body: LinearLayout, tint: Int, value: Float, onChange: (Float) -> Unit) {
    // A numeric readout, so the level is legible without judging the gradient.
    val header = LinearLayout(context)
    header.orientation = LinearLayout.HORIZONTAL
    header.gravity = Gravity.CENTER_VERTICAL
    header.addView(context.label("Opacity", 13f, PdfUi.INK_SOFT, Typeface.BOLD), LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    val readout = context.label("${(value * 100f).roundToInt()}%", 13f, PdfUi.MUTED, Typeface.BOLD)
    header.addView(readout)
    body.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
      topMargin = dpi(18f)
      bottomMargin = dpi(8f)
    })
    val bar = PdfOpacityBar(context)
    bar.tint = tint or 0xFF000000.toInt()
    bar.value = value
    bar.contentDescription = "Opacity"
    bar.onChange = { next ->
      readout.text = "${(next * 100f).roundToInt()}%"
      onChange(next)
    }
    body.addView(bar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dpi(46f)).apply {
      bottomMargin = dpi(4f)
    })
  }

  /** One thickness preset: a line drawn at its real weight, boxed when chosen. */
  private fun thicknessChip(step: Int): View {
    val active = strokeStep == step
    val frame = FrameLayout(context)
    frame.isClickable = true
    frame.contentDescription = "Line thickness ${step + 1}"
    frame.background = if (active) {
      rounded(PdfUi.ACCENT_SOFT, 10f, context, PdfUi.ACCENT, 1.4f)
    } else {
      rounded(0x00000000, 10f, context)
    }
    // 46 dp tall cells keep the thickness presets easy to hit.
    frame.minimumHeight = dpi(46f)
    val line = View(context)
    line.setBackgroundColor(inkColor or 0xFF000000.toInt())
    frame.addView(
      line,
      FrameLayout.LayoutParams(dpi(24f + step * 3f), dpi(1.8f + step * 1.8f).coerceAtLeast(1), Gravity.CENTER),
    )
    frame.setOnClickListener {
      strokeStep = step
      applyToolColors()
      rebuildDrawSheet()
    }
    return frame
  }

  private fun markupMode(style: String): View {
    val active = highlightStyle == style
    val label = when (style) {
      "underline" -> "Underline"
      "strikeout" -> "Strike"
      else -> "Highlight"
    }
    val chip = context.label(label, 13f, if (active) Color.WHITE else PdfUi.INK, Typeface.BOLD)
    chip.gravity = Gravity.CENTER
    chip.isClickable = true
    chip.setPadding(dpi(13f), dpi(9f), dpi(13f), dpi(9f))
    chip.background = rounded(if (active) PdfUi.ACCENT else 0xFFF2F3F5.toInt(), 12f, context)
    chip.setOnClickListener {
      if (highlightStyle != style) {
        highlightStyle = style
        applyToolColors()
      }
      rebuildMarkupSheet()
    }
    return chip
  }

  private fun markupHint(): String = when (highlightStyle) {
    "underline" -> "Drag across text to underline it."
    "strikeout" -> "Drag across text to strike it through."
    else -> "Drag across text to highlight it."
  }

  // ---- search / more / edit ----------------------------------------------

  private fun showSearchSheet() {
    sheet.setBody { body ->
      body.addView(sheetTitle("Search document"))
      val row = LinearLayout(context)
      row.orientation = LinearLayout.HORIZONTAL
      row.gravity = Gravity.CENTER_VERTICAL
      row.background = rounded(0xFFF7F8FA.toInt(), 14f, context, 0xFFDDE0E5.toInt(), 1f)
      row.setPadding(dpi(14f), 0, dpi(4f), 0)
      val field = EditText(context)
      field.hint = "Find in document"
      field.textSize = 15f
      field.setSingleLine(true)
      field.imeOptions = EditorInfo.IME_ACTION_SEARCH
      field.background = null
      field.setTextColor(PdfUi.INK)
      field.setHintTextColor(PdfUi.MUTED)
      row.addView(field, LinearLayout.LayoutParams(0, dpi(50f), 1f))
      val go = PdfIconButton(context)
      go.iconView.icon = PdfIcon.SEARCH
      go.iconView.color = PdfUi.ACCENT
      row.addView(go, LinearLayout.LayoutParams(dpi(42f), dpi(42f)))
      body.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = dpi(10f)
      })

      val results = LinearLayout(context)
      results.orientation = LinearLayout.VERTICAL
      body.addView(results, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = dpi(8f)
      })

      fun runSearch() {
        val query = field.text.toString().trim()
        if (query.isEmpty()) return
        hideKeyboard(field)
        results.removeAllViews()
        results.addView(context.label("Searching…", 13f, PdfUi.MUTED).apply { setPadding(0, dpi(10f), 0, 0) })
        surface.searchText(query) { hits ->
          results.removeAllViews()
          if (hits.isEmpty()) {
            results.addView(context.label("No matches for \u201C$query\u201D", 13f, PdfUi.MUTED).apply {
              setPadding(0, dpi(10f), 0, 0)
            })
            return@searchText
          }
          results.addView(context.label("${hits.size} page${if (hits.size == 1) "" else "s"} with matches", 12f, PdfUi.MUTED).apply {
            setPadding(0, dpi(10f), 0, dpi(4f))
          })
          hits.take(12).forEach { hitPage ->
            val rowItem = LinearLayout(context)
            rowItem.orientation = LinearLayout.HORIZONTAL
            rowItem.gravity = Gravity.CENTER_VERTICAL
            rowItem.setPadding(dpi(6f), 0, dpi(6f), 0)
            rowItem.isClickable = true
            rowItem.background = rounded(0x00000000, 10f, context)
            rowItem.addView(
              context.label("Page $hitPage", 14.5f, PdfUi.INK),
              LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
            )
            rowItem.addView(PdfIconView(context).apply {
              icon = PdfIcon.CHEVRON_RIGHT
              color = PdfUi.MUTED
            }, LinearLayout.LayoutParams(dpi(18f), dpi(18f)))
            rowItem.setOnClickListener {
              // A direct jump, not an animation: the sheet closes at the same
              // moment and an interpolated scroll would lose its frames.
              surface.scrollToPage(hitPage, false)
              sheet.close()
            }
            results.addView(rowItem, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dpi(46f)))
          }
        }
      }

      go.setOnClickListener { runSearch() }
      field.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_SEARCH) {
          runSearch()
          true
        } else {
          false
        }
      }
      focus(field)
    }
    sheet.open()
  }

  private fun showMoreSheet() {
    sheet.setBody { body ->
      body.addView(sheetTitle("More tools"))
      body.addView(actionRow(PdfIcon.SEARCH, "Search document", null) { showSearchSheet() })
      body.addView(actionRow(PdfIcon.SPEAKER, "Read aloud", "Current page") {
        sheet.close()
        onReadAloud?.invoke(page)
      })
      body.addView(actionRow(PdfIcon.SHARE, "Share document", null) {
        sheet.close()
        onShare?.invoke()
      })
      body.addView(actionRow(PdfIcon.FIT_WIDTH, "Fit width", null) {
        surface.fitWidth()
        sheet.close()
      })
      body.addView(actionRow(PdfIcon.FIT_PAGE, "Fit page", null) {
        surface.fitPage()
        sheet.close()
      })
      body.addView(actionRow(PdfIcon.TRASH, "Clear all marks", "${marks.size}") {
        confirmClearMarks()
      })
    }
    sheet.open()
  }

  private fun confirmClearMarks() {
    if (marks.isEmpty()) {
      showHint("There are no marks to clear")
      return
    }
    val dialog = AlertDialog.Builder(context)
      .setTitle("Clear all marks?")
      .setMessage("Every highlight, note and signature in this document will be removed. You can undo this.")
      .setNegativeButton("Cancel", null)
      .setPositiveButton("Clear") { _, _ ->
        surface.clearMarks()
        sheet.close()
        showHint("All marks removed — undo to restore")
      }
      .create()
    dialog.show()
  }

  private fun showEditSheet() {
    sheet.setBody { body ->
      body.addView(sheetTitle("Edit PDF"))
      body.addView(actionRow(PdfIcon.HIGHLIGHT, "Highlight", if (tool == PdfTool.HIGHLIGHT) "Active" else "Drag") {
        sheet.close()
        setTool(PdfTool.HIGHLIGHT)
        showHint("Drag across the page to highlight")
      })
      body.addView(actionRow(PdfIcon.DRAW, "Draw", if (tool == PdfTool.DRAW) "Active" else "Freehand") {
        sheet.close()
        setTool(PdfTool.DRAW)
        showHint("Draw on the page with one finger")
      })
      body.addView(actionRow(PdfIcon.COMMENT, "Add comment", "Tap the page") {
        sheet.close()
        setTool(PdfTool.COMMENT)
        showHint("Tap the page to drop a comment")
      })
      body.addView(actionRow(PdfIcon.SIGN, "Fill & Sign", null) {
        sheet.close()
        showSignSheet()
      })
      body.addView(actionRow(PdfIcon.UNDO, "Undo last change", "${marks.size}") {
        sheet.close()
        surface.undoMark()
      })
      body.addView(actionRow(PdfIcon.TRASH, "Clear all marks", null) {
        confirmClearMarks()
      })
    }
    sheet.open()
  }

  private fun sheetTitle(text: String): View = context.label(text, 19f, PdfUi.INK, Typeface.BOLD).apply {
    setPadding(0, dpi(2f), 0, dpi(6f))
  }

  private fun composerField(hintText: String): EditText {
    return EditText(context).apply {
      hint = hintText
      textSize = 15.5f
      setTextColor(PdfUi.INK)
      setHintTextColor(PdfUi.MUTED)
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
      minLines = 3
      gravity = Gravity.TOP
      setPadding(dpi(14f), dpi(12f), dpi(14f), dpi(12f))
      background = rounded(0xFFF7F8FA.toInt(), 14f, context, 0xFFDDE0E5.toInt(), 1f)
    }
  }

  private fun actionRow(icon: PdfIcon, title: String, detail: String?, action: () -> Unit): View {
    val row = LinearLayout(context)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    row.setPadding(0, dpi(12f), 0, dpi(12f))
    row.isClickable = true
    val iconView = PdfIconView(context)
    iconView.icon = icon
    iconView.color = PdfUi.INK_SOFT
    iconView.weight = 1.45f
    row.addView(iconView, LinearLayout.LayoutParams(dpi(22f), dpi(22f)))
    row.addView(context.label(title, 15f, PdfUi.INK), LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
      leftMargin = dpi(14f)
    })
    if (detail != null) {
      row.addView(context.label(detail, 12.5f, PdfUi.MUTED))
    }
    row.setOnClickListener { action() }
    return row
  }

  private fun primaryButton(text: String, action: () -> Unit): View {
    return context.label(text, 15f, Color.WHITE, Typeface.BOLD).apply {
      gravity = Gravity.CENTER
      isClickable = true
      background = rounded(PdfUi.ACCENT, 14f, context)
      setOnClickListener { action() }
    }
  }

  // ---- helpers ------------------------------------------------------------

  private fun updateHintMargin() {
    val params = hint.layoutParams as? LayoutParams ?: return
    val extra = if (optionsBar.visibility == VISIBLE) dpi(54f) else 0
    params.bottomMargin = dpi(78f) + extra
    hint.layoutParams = params
  }

  private fun showHint(message: String) {
    hint.text = message
    hint.removeCallbacks(hintHide)
    hint.animate().alpha(1f).setDuration(140L).start()
    hint.postDelayed(hintHide, 2200L)
  }

  private fun fadeHint() {
    hint.animate().alpha(0f).setDuration(220L).start()
  }

  private fun focus(field: EditText) {
    field.requestFocus()
    field.postDelayed({
      val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
      imm?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
    }, 180L)
  }

  private fun hideKeyboard(field: EditText) {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    imm?.hideSoftInputFromWindow(field.windowToken, 0)
    field.clearFocus()
  }

  private fun emitMarks() {
    val current = surface.marks
    markStore.schedule(current)
    onMarksChanged?.invoke(PdfMarkCodec.encode(current))
  }

  // ---- insets -------------------------------------------------------------

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    requestApplyInsets()
  }

  override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
    // Safe areas belong to the host (the viewer is placed inside a safe-area
    // container), so only the keyboard needs lifting here: it is the one inset
    // the host cannot reserve space for ahead of time.
    val ime = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      insets.getInsets(WindowInsets.Type.ime()).bottom
    } else {
      0
    }
    sheet.setImeInset(ime)
    return super.onApplyWindowInsets(insets)
  }

  /** A signature pad that records strokes normalised to its own box. */
  private class SignaturePadView(context: Context) : View(context) {
    private val strokes = mutableListOf<MutableList<FloatArray>>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = 3.2f * resources.displayMetrics.density
      strokeCap = Paint.Cap.ROUND
      strokeJoin = Paint.Join.ROUND
      color = PdfUi.INK
    }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = 1.4f * resources.displayMetrics.density
      color = 0xFFD9DCE2.toInt()
      pathEffect = android.graphics.DashPathEffect(floatArrayOf(9f * resources.displayMetrics.density, 7f * resources.displayMetrics.density), 0f)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = 0xFFAFB4BD.toInt()
      textSize = 13f * resources.displayMetrics.density
      textAlign = Paint.Align.CENTER
    }

    init {
      background = rounded(0xFFFAFBFC.toInt(), 16f, context)
      isClickable = true
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
      val nx = (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
      val ny = (event.y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
      when (event.actionMasked) {
        android.view.MotionEvent.ACTION_DOWN -> {
          strokes.add(mutableListOf(floatArrayOf(nx, ny)))
          invalidate()
        }
        android.view.MotionEvent.ACTION_MOVE -> {
          strokes.lastOrNull()?.add(floatArrayOf(nx, ny))
          invalidate()
        }
      }
      return true
    }

    fun clear() {
      strokes.clear()
      invalidate()
    }

    fun normalizedStrokes(): List<FloatArray> {
      return strokes.filter { it.size >= 3 }.map { stroke ->
        val flat = FloatArray(stroke.size * 2)
        stroke.forEachIndexed { index, point ->
          flat[index * 2] = point[0]
          flat[index * 2 + 1] = point[1]
        }
        flat
      }
    }

    override fun onDraw(canvas: Canvas) {
      super.onDraw(canvas)
      val box = RectF(1.5f, 1.5f, width - 1.5f, height - 1.5f)
      canvas.drawRoundRect(box, 16f * resources.displayMetrics.density, 16f * resources.displayMetrics.density, border)
      if (strokes.isEmpty()) {
        canvas.drawText(
          "Sign here",
          width / 2f,
          height / 2f + hintPaint.textSize / 3f,
          hintPaint,
        )
        return
      }
      strokes.forEach { stroke ->
        if (stroke.size < 2) return@forEach
        val path = android.graphics.Path()
        path.moveTo(stroke[0][0] * width, stroke[0][1] * height)
        for (i in 1 until stroke.size) {
          path.lineTo(stroke[i][0] * width, stroke[i][1] * height)
        }
        canvas.drawPath(path, paint)
      }
    }
  }
}
