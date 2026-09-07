package top.imsyy.splayer_next.android

import android.content.ComponentName
import android.content.pm.PackageManager
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * 桌面图标颜色切换插件：通过 activity-alias 启用态切换桌面图标配色变体。
 * 别名清单见 AndroidManifest.xml，MainActivity 永不禁用，仅切换别名。
 */
@CapacitorPlugin(name = "AndroidAppIcon")
class AndroidAppIconPlugin : Plugin() {
  /** 变体名 → activity-alias 类名，顺序与设置页色板一致 */
  private val iconAliases =
    linkedMapOf(
      "green" to ".IconGreen",
      "red" to ".IconRed",
      "blue" to ".IconBlue",
      "purple" to ".IconPurple",
      "orange" to ".IconOrange",
      "pink" to ".IconPink",
    )

  /** manifest 中默认启用的别名（绿色），组件启用态为 DEFAULT 时按它兜底 */
  private val defaultIcon = "green"

  /**
   * 读取当前生效的桌面图标变体名
   * @returns 当前变体名，如 "green"
   */
  @PluginMethod
  fun getIcon(call: PluginCall) {
    val result = JSObject()
    result.put("icon", resolveCurrentIcon())
    call.resolve(result)
  }

  /**
   * 切换桌面图标变体：启用目标别名并禁用其余别名
   * @param icon - 目标变体名（green / red / blue / purple / orange / pink）
   */
  @PluginMethod
  fun setIcon(call: PluginCall) {
    val icon = call.getString("icon", defaultIcon) ?: defaultIcon
    val targetAlias = iconAliases[icon]
    if (targetAlias == null) {
      call.reject("UNSUPPORTED_ICON")
      return
    }
    val pm = context.packageManager
    // 先启用目标再禁用其余，避免出现短暂无 LAUNCHER 入口的状态
    pm.setComponentEnabledSetting(
      componentFor(targetAlias),
      PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
      PackageManager.DONT_KILL_APP,
    )
    for ((name, alias) in iconAliases) {
      if (name == icon) continue
      pm.setComponentEnabledSetting(
        componentFor(alias),
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.DONT_KILL_APP,
      )
    }
    call.resolve()
  }

  /**
   * 构造别名组件：包名部分用运行时安装包名（applicationId），
   * 类名部分用 manifest namespace（与 MainActivity 同包）拼接别名相对类名。
   * 两者在 build.gradle 中配置不同，混用会导致 Unknown component 崩溃
   * @param alias - manifest 中的别名相对类名，如 ".IconGreen"
   * @returns 对应别名组件
   */
  private fun componentFor(alias: String): ComponentName {
    val namespace = MainActivity::class.java.packageName
    return ComponentName(context.packageName, "$namespace$alias")
  }

  /**
   * 遍历别名组件启用态，找出当前生效的变体
   * @returns 当前生效的变体名，无法识别时回退默认绿色
   */
  private fun resolveCurrentIcon(): String {
    val pm = context.packageManager
    for ((name, alias) in iconAliases) {
      val state = pm.getComponentEnabledSetting(componentFor(alias))
      val enabled =
        when (state) {
          PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
          PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
          else -> name == defaultIcon
        }
      if (enabled) return name
    }
    return defaultIcon
  }
}
