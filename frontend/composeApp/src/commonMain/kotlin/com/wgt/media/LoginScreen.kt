package com.wgt.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wgt.feature.media.MediaService
import com.wgt.platform.logger.logger
import kotlinx.coroutines.launch

private const val TAG = "LoginScreen"

/**
 * 登录页。
 *
 * 三字段：服务端地址 / 用户名 / 密码。地址默认取 [SettingsState.backendUrl]，登录成功前
 * 同时把它存盘（保证登录用的地址与后续媒体请求一致）。登录按钮调
 * [MediaService.login]，成功 → [AuthState.saveSession] 存 token + [onLoggedIn] 回调
 * （App 路由守卫据此切到主界面）。
 *
 * 失败以 Snackbar 反馈：地址不通、凭据错误、用户名占用等分别给可读提示。
 * 底部 [onSwitchToRegister] 切到注册页。
 *
 * @param onLoggedIn 登录成功回调（App 据此离开登录页）
 * @param onSwitchToRegister 切换到注册页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    onSwitchToRegister: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 服务端地址默认取已存设置；为空时给占位提示，不阻断输入。
    var serverUrl by remember { mutableStateOf(SettingsState.backendUrl) }

    // V8 开发环境预填——地址为默认值或空时自动填入 admin 凭据，省去每次手输。
    // 生产环境将 DEV_DEFAULT_USERNAME/PASSWORD 置空即自动禁用预填（isDevDefault 仍为 true 但
    // 填入的是空串，等同于不预填），避免真机用户看到无关的预填账号。
    val isDevDefault = serverUrl.isBlank() || serverUrl == "http://localhost:8080"
    var username by remember {
        mutableStateOf(if (isDevDefault && SettingsState.DEV_DEFAULT_USERNAME.isNotEmpty()) SettingsState.DEV_DEFAULT_USERNAME else "")
    }
    var password by remember {
        mutableStateOf(if (isDevDefault && SettingsState.DEV_DEFAULT_PASSWORD.isNotEmpty()) SettingsState.DEV_DEFAULT_PASSWORD else "")
    }
    var passwordVisible by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("登录", fontWeight = FontWeight.Bold, fontSize = 20.sp) }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // 应用标题，明确这是哪个服务的登录。
            Text(
                "Media Manager",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "登录以访问你的媒体库",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ---- 服务端地址 ----
            Text("服务端地址", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isSubmitting,
                placeholder = { Text("http://192.168.31.251:8080") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

            // ---- 用户名 ----
            Text("用户名", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isSubmitting,
                placeholder = { Text("用户名") }
            )

            // ---- 密码 ----
            Text("密码", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isSubmitting,
                placeholder = { Text("密码") },
                visualTransformation = if (passwordVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Text(if (passwordVisible) "隐藏" else "显示", style = MaterialTheme.typography.labelSmall)
                    }
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ---- 登录按钮 ----
            Button(
                onClick = {
                    if (isSubmitting) return@Button
                    val u = username.trim()
                    val p = password
                    val url = serverUrl.trim()
                    if (u.isEmpty() || p.isEmpty()) {
                        scope.launch { snackbarHostState.showSnackbar("请输入用户名和密码") }
                        return@Button
                    }
                    // 登录前把地址落地，保证后续媒体请求用同一地址。
                    SettingsState.saveBackendUrl(url)
                    MediaService.setBackendUrl(url)
                    isSubmitting = true
                    scope.launch {
                        val outcome = MediaService.login(u, p)
                        isSubmitting = false
                        val r = outcome.result
                        if (outcome.success && r != null) {
                            AuthState.saveSession(r.token, r.user.username.ifEmpty { u }, r.user.id.ifEmpty { null })
                            logger.info(TAG, "login success, navigating to main")
                            onLoggedIn()
                        } else {
                            val msg = humanizeAuthError(outcome.error, outcome.httpStatus, "登录失败")
                            snackbarHostState.showSnackbar(msg)
                        }
                    }
                },
                enabled = !isSubmitting && username.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("登录中…")
                } else {
                    Text("登录")
                }
            }

            // ---- 切换注册 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("还没有账号？", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onSwitchToRegister, enabled = !isSubmitting) {
                    Text("注册")
                }
            }
        }
    }
}

/**
 * 把后端原始 error 文本翻译为用户可读提示。
 *
 * 后端错误结构见 [MediaService.authRequest]：
 * - 403（注册关闭）、409（用户名占用）由状态码精确定位
 * - 连接异常类（"无法连接服务器"）单独提示，便于区分网络问题
 * - 其余直接展示后端 error 文本（后端文案本身面向用户，如"username and password are required"）
 *
 * 通用兜底用 [fallback]（如"登录失败"/"注册失败"）。
 */
internal fun humanizeAuthError(error: String?, httpStatus: Int, fallback: String): String {
    // 网络层已组装的连接失败提示，原样显示。
    if (error != null && error.startsWith("无法连接服务器")) return error
    return when (httpStatus) {
        403 -> "注册已关闭，请联系管理员"
        409 -> "用户名已存在"
        401 -> "用户名或密码错误"
        400 -> error ?: "用户名或密码错误"
        else -> error ?: fallback
    }
}
