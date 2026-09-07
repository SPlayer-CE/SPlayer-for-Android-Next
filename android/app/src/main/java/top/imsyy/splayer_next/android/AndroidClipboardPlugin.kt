package top.imsyy.splayer_next.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * 系统剪贴板插件
 * WebView 的 navigator.clipboard 在部分 ROM 上被权限门控拒绝或静默失败，读写统一走 ClipboardManager
 */
@CapacitorPlugin(name = "AndroidClipboard")
class AndroidClipboardPlugin : Plugin() {
  private val clipboard: ClipboardManager?
    get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

  /**
   * 写入文本到系统剪贴板
   */
  @PluginMethod
  fun writeText(call: PluginCall) {
    val text = call.getString("text", "") ?: ""
    val manager = clipboard
    if (manager == null) {
      call.reject("CLIPBOARD_UNAVAILABLE")
      return
    }
    // 部分 ROM 的剪贴板管控会抛 SecurityException 等；Capacitor 桥不捕获插件异常，
    // 不显式 reject 会导致前端 Promise 永久挂起
    try {
      manager.setPrimaryClip(ClipData.newPlainText("text", text))
      call.resolve()
    } catch (error: Exception) {
      call.reject("CLIPBOARD_WRITE_FAILED", error)
    }
  }

  /**
   * 读取系统剪贴板文本
   */
  @PluginMethod
  fun readText(call: PluginCall) {
    val manager = clipboard
    if (manager == null) {
      call.reject("CLIPBOARD_UNAVAILABLE")
      return
    }
    try {
      val text =
        manager.primaryClip
          ?.getItemAt(0)
          ?.coerceToText(context)
          ?.toString() ?: ""
      val result = JSObject()
      result.put("text", text)
      call.resolve(result)
    } catch (error: Exception) {
      call.reject("CLIPBOARD_READ_FAILED", error)
    }
  }
}
