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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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

private const val TAG = "RegisterScreen"
/** 密码最短长度——与后端弱口令策略保持一致（后端 ErrInvalidCredentials 兜底校验）。 */
private const val MIN_PASSWORD_LEN = 6

/**
 * 注册页。
 *
 * 字段：服务端地址 / 用户名 / 密码 / 确认密码。成功 → 直接存 token + 回主界面（注册即登录）。
 *
 * allow_signup 由后端控制且不暴露，故本页无条件展示——用户提交后若 403 则提示"注册已关闭"
 * （[humanizeAuthError] 处理）。这是契约约束下的合理实现：前端无法预判，只能边试边反馈。
 *
 * @param onRegistered 注册成功回调
 * @param onSwitchToLogin 切回登录页
 * @param onBack 返回（关闭注册页）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(
    onRegistered: () -> Unit,
    onSwitchToLogin: () -> Unit,
    onBack: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf(SettingsState.backendUrl) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("注册", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", style = MaterialTheme.typography.titleLarge)
                    }
                }
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
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "创建新账号",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "注册成功后自动登录",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(8.dp))

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
            Text("密码（至少 $MIN_PASSWORD_LEN 位）", style = MaterialTheme.typography.labelLarge)
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

            // ---- 确认密码 ----
            Text("确认密码", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isSubmitting,
                placeholder = { Text("再次输入密码") },
                visualTransformation = if (passwordVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ---- 注册按钮 ----
            Button(
                onClick = {
                    if (isSubmitting) return@Button
                    val u = username.trim()
                    val p = password
                    val url = serverUrl.trim()
                    when {
                        u.isEmpty() -> scope.launch { snackbarHostState.showSnackbar("请输入用户名") }
                        p.length < MIN_PASSWORD_LEN -> scope.launch {
                            snackbarHostState.showSnackbar("密码至少 $MIN_PASSWORD_LEN 位")
                        }
                        p != confirmPassword -> scope.launch { snackbarHostState.showSnackbar("两次密码不一致") }
                        else -> {
                            SettingsState.saveBackendUrl(url)
                            MediaService.setBackendUrl(url)
                            isSubmitting = true
                            scope.launch {
                                val outcome = MediaService.register(u, p)
                                isSubmitting = false
                                val r = outcome.result
                                if (outcome.success && r != null) {
                                    AuthState.saveSession(r.token, r.user.username.ifEmpty { u }, r.user.id.ifEmpty { null })
                                    logger.info(TAG, "register success, auto-login")
                                    onRegistered()
                                } else {
                                    val msg = humanizeAuthError(outcome.error, outcome.httpStatus, "注册失败")
                                    snackbarHostState.showSnackbar(msg)
                                }
                            }
                        }
                    }
                },
                enabled = !isSubmitting && username.isNotBlank() && password.isNotEmpty() && confirmPassword.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("注册中…")
                } else {
                    Text("注册")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("已有账号？", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onSwitchToLogin, enabled = !isSubmitting) {
                    Text("去登录")
                }
            }
        }
    }
}
