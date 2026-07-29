package com.wgt.media

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import mediamanager.composeapp.generated.resources.Res
import mediamanager.composeapp.generated.resources.ic_arrow_back
import mediamanager.composeapp.generated.resources.ic_close
import mediamanager.composeapp.generated.resources.ic_search
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults

private const val SEARCH_DEBOUNCE_MS = 300L

/**
 * 可展开搜索栏。
 *
 * 收起态：仅一个搜索图标按钮 + 灰色"搜索"提示，占用空间小，不干扰浏览。
 * 展开态：返回箭头 + 输入框 + 清除按钮一行，输入框自动聚焦并唤起键盘实时过滤。
 *
 * 输入文案由本地 [BasicTextField] 持有；为满足"debounce 300ms 实时过滤"，[onDebouncedQueryChange]
 * 在输入停止 300ms 后才上抛，避免每个字符都触发 ViewModel 的 list 过滤重组。清除/收起时立即
 * 上抛空串，让列表即时恢复无过滤态，不必等去抖窗口。
 *
 * @param expanded 当前是否展开（由外部持有，便于与筛选条联动）
 * @param onExpandedChange 展开/收起回调
 * @param onDebouncedQueryChange debounce 300ms 后的查询文案回调（驱动 ViewModel 过滤）
 * @param onSearchSubmit IME 搜索键提交时的回执，可立即触发一次过滤
 * @param modifier
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun SearchBar(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onDebouncedQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 输入框本地文案：即时跟随键盘输入。
    var queryText by remember { mutableStateOf("") }
    // 控制清除按钮显隐（仅在有文案时淡入），避免空文案时误触清除。
    var queryVisible by remember { mutableStateOf(false) }
    // 展开时聚焦输入框，自动唤起键盘，省去用户二次点击。
    val focusRequester = remember { FocusRequester() }

    // debounce：监听 queryText 变化，停顿 300ms 后上抛去抖后的查询。
    // distinctUntilChanged 避免相同值重复触发过滤重组。
    LaunchedEffect(Unit) {
        snapshotFlow { queryText }
            .distinctUntilChanged()
            .collect {
                queryVisible = it.isNotEmpty()
                delay(SEARCH_DEBOUNCE_MS)
                onDebouncedQueryChange(it.trim())
            }
    }

    // 展开瞬间请求焦点，让输入框立即可输入、键盘自动弹出。
    LaunchedEffect(expanded) {
        if (expanded) {
            // 延后一帧等输入框挂载完成，再请求焦点更稳妥。
            delay(50)
            runCatching { focusRequester.requestFocus() }
        }
    }

    // 用 Column 自上而下垂直堆叠"搜索框"与"搜索历史"，历史永远在搜索框下方，
    // 不会因 align/Box 叠层遮盖输入框。
    Column(modifier = modifier.fillMaxWidth()) {
        // 搜索框：始终填满父宽，避免收起态 CenterEnd 偏移造成的历史标签错位。
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 收起态：搜索图标按钮，点击展开。
                // 展开态：返回箭头，点击收起并清空查询（收起即退出搜索，恢复完整列表）。
                IconButton(
                    onClick = {
                        if (expanded) {
                            // 收起：清空输入与去抖查询，恢复无过滤态。
                            queryText = ""
                            queryVisible = false
                            onDebouncedQueryChange("")
                            onExpandedChange(false)
                        } else {
                            onExpandedChange(true)
                        }
                    }
                ) {
                    Icon(
                        painter = painterResource(
                            if (expanded) Res.drawable.ic_arrow_back else Res.drawable.ic_search
                        ),
                        contentDescription = if (expanded) "收起搜索" else "搜索",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (expanded) {
                    Spacer(modifier = Modifier.width(4.dp))

                    // 输入框：BasicTextField 自定义样式，与动态色调协调。
                    BasicTextField(
                        value = queryText,
                        onValueChange = { queryText = it },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { state ->
                                // 仅清空查询，不自动收起——收起由 IconButton 返回箭头控制
                                if (!state.isFocused && queryText.isEmpty()) {
                                    onDebouncedQueryChange("")
                                }
                            },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            onSearchSubmit()
                        }),
                        decorationBox = { innerTextField ->
                            // placeholder：无文案时显示灰色提示。
                            if (queryText.isEmpty()) {
                                Text(
                                    "搜索媒体名称",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    fontSize = 15.sp
                                )
                            }
                            innerTextField()
                        }
                    )

                    // 清除按钮：仅在有文案时淡入显示；点击清空输入并立即上抛空串。
                    AnimatedVisibility(
                        visible = queryVisible,
                        enter = fadeIn(tween(150)),
                        exit = fadeOut(tween(120))
                    ) {
                        IconButton(
                            onClick = {
                                queryText = ""
                                queryVisible = false
                                onDebouncedQueryChange("")
                            }
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_close),
                                contentDescription = "清除",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    // 收起态占位文案：提示可点击搜索图标开始过滤。
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "搜索",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }

        // 搜索历史：仅展开态且输入为空时显示，位于搜索框正下方，用 Spacer 明确分隔。
        // remember 缓存避免每次重组都重读 SettingsStorage。
        if (expanded && queryText.isEmpty()) {
            val history = remember { SearchHistory.load() }
            if (history.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(history.size) { index ->
                        val historicQuery = history[index]
                        AssistChip(
                            onClick = {
                                queryText = historicQuery
                                queryVisible = true
                                onDebouncedQueryChange(historicQuery)
                                SearchHistory.add(historicQuery)
                            },
                            label = { Text(historicQuery, fontSize = 13.sp) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        )
                    }
                }
            }
        }
    }
}
