package dev.hbox.pdfexample

import android.content.Intent
import androidx.core.content.FileProvider
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import java.io.File

/**
 * Shares a cached document as a real attachment.
 *
 * React Native's JS `Share` module only sends `EXTRA_TEXT` on Android, which is
 * why sharing a document produced a bare `file:///...` line. This module hands
 * the file over through a FileProvider content URI with a read grant, so mail
 * and chat apps receive the actual PDF/DOCX.
 */
class DocumentShareModule(private val context: ReactApplicationContext) :
  ReactContextBaseJavaModule(context) {

  override fun getName(): String = "DocumentShare"

  @ReactMethod
  fun shareFile(path: String, mimeType: String, title: String, promise: Promise) {
    try {
      val file = File(path.removePrefix("file://"))
      if (!file.exists() || file.length() <= 0L) {
        promise.reject("E_NOT_FOUND", "The document file is no longer available")
        return
      }

      val uri = FileProvider.getUriForFile(context, "${context.packageName}.documents", file)
      val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, title)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      val chooser = Intent.createChooser(send, "Share document").apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(chooser)
      promise.resolve(true)
    } catch (error: Throwable) {
      promise.reject("E_SHARE_FAILED", error.message ?: "Could not open the share sheet", error)
    }
  }
}
