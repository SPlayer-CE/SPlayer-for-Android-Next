package top.imsyy.splayer_next.android.server

/** 外部 API 服务配置 */
data class ExternalApiConfig(
  var enabled: Boolean = false,
  var wsEnabled: Boolean = false,
  var allowLan: Boolean = false,
  var port: Int = 6688,
  var token: String = "",
)
