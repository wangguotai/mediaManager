package com.wgt.media

/**
 * 后端连通性测试 —— 平台差异化的原生 HTTP 实现。
 *
 * 为何不走 [com.wgt.feature.media.MediaService]：composeApp 模块自身未引入
 * ktor 依赖，且 [com.wgt.feature.media.MediaService] 写死了 BASE_URL（在
 * feature-media 模块内、超出本模块文件边界），无法复用设置页输入的地址。
 * 因此用各平台原生 HTTP（Android: HttpURLConnection；iOS: NSURLSession）
 * 对输入地址发 HEAD 探测，返回是否可达。
 *
 * 期望方法返回 null 表示成功（即可达），返回非空字符串为失败原因，
 * 便于设置页直接展示。
 *
 * @param backendUrl 后端地址，形如 `http://10.0.2.2:8080`
 * @return null=可达；非空=失败描述
 */
expect suspend fun pingBackend(backendUrl: String): String?
