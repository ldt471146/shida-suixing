package cn.gxnu.campus.ui.screens

import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.gxnu.campus.network.PortalCompletion
import cn.gxnu.campus.network.PortalCompletionPolicy
import cn.gxnu.campus.network.VisiblePortalSession
import kotlinx.coroutines.delay

/**
 * The school's own login page, embedded. The page keeps filling the form by itself; this screen
 * adds the exits a hidden attempt cannot offer - a visible retry, a reload, a close, and the
 * confirmation that ends the flow once this Wi-Fi really reaches the internet.
 */
@Composable
fun OfficialPortalScreen(
    session: VisiblePortalSession,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var view by remember(session) { mutableStateOf<WebView?>(null) }
    var loading by remember(session) { mutableStateOf(true) }
    var autoFilled by remember(session) { mutableStateOf(false) }
    val completion by session.completion.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    DisposableEffect(session) {
        onDispose { session.destroy() }
    }

    LaunchedEffect(session) {
        // Probing lives with this screen: leaving the page cancels it along with the composition.
        session.completion.start(scope)
    }

    LaunchedEffect(session, completion) {
        // Success returns the user to the app without a tap; the panel still offers one right away.
        val confirmation = PortalCompletionPolicy.autoReturnDelay(completion) ?: return@LaunchedEffect
        delay(confirmation)
        onClose()
    }

    LaunchedEffect(session, view, loading) {
        if (view == null || loading) return@LaunchedEffect
        // The school builds its form asynchronously from its own configuration.
        repeat(12) {
            delay(600)
            if (autoFilled) return@LaunchedEffect
            if (session.autoFill()) autoFilled = true
        }
    }

    Column(modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text("学校认证页面", style = MaterialTheme.typography.titleMedium)
                        Text(
                            progressNotice(completion, autoFilled),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = {
                            session.reload()
                            loading = true
                            autoFilled = false
                            // A manual retry deserves a fresh deadline rather than a spent one.
                            session.completion.start(scope)
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("重新加载")
                    }
                }
                CompletionPanel(completion, onClose)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { session.createView().also { view = it } },
                update = { }
            )
            if (loading) {
                LaunchedEffect(Unit) { delay(1_200); loading = false }
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.size(12.dp))
                    Text("正在打开学校登录页…", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** The line under the title; it is the only place that says what the page is still doing. */
private fun progressNotice(completion: PortalCompletion, autoFilled: Boolean): String = when (completion) {
    PortalCompletion.Checking -> if (autoFilled) "已自动填写，正在提交认证。" else "正在准备学校登录页，可手动完成。"
    PortalCompletion.Online -> "认证成功，正在返回首页。"
    is PortalCompletion.Manual -> "未自动确认联网，可在此页面手动完成登录。"
}

/**
 * The outcome sits above the page instead of replacing it: a manual login has to stay possible
 * while the watch is running and after it has given up.
 */
@Composable
private fun CompletionPanel(completion: PortalCompletion, onClose: () -> Unit) {
    when (completion) {
        PortalCompletion.Checking -> Unit
        PortalCompletion.Online -> Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("认证成功，校园网已可上网", style = MaterialTheme.typography.bodyMedium)
                    Text("即将自动返回首页。", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("完成")
                }
            }
        }
        is PortalCompletion.Manual -> Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(completion.reason, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
