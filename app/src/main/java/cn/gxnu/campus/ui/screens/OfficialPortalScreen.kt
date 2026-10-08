package cn.gxnu.campus.ui.screens

import android.webkit.WebView
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.CloudOff
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.gxnu.campus.network.PortalCompletion
import cn.gxnu.campus.network.PortalCompletionPolicy
import cn.gxnu.campus.network.PortalPageState
import cn.gxnu.campus.network.VisiblePortalSession
import cn.gxnu.campus.ui.common.CampusCard
import cn.gxnu.campus.ui.common.CampusPill
import cn.gxnu.campus.ui.theme.CampusSpace
import cn.gxnu.campus.ui.theme.LocalCampusPalette
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
    var autoFilled by remember(session) { mutableStateOf(false) }
    val completion by session.completion.state.collectAsStateWithLifecycle()
    val page by session.page.collectAsStateWithLifecycle()
    val ready = page is PortalPageState.Ready
    val scope = rememberCoroutineScope()
    val retry: () -> Unit = {
        session.reload()
        autoFilled = false
        // A manual retry deserves a fresh deadline rather than a spent one.
        session.completion.start(scope)
    }

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

    LaunchedEffect(session, view, ready) {
        // The bridge only runs into a page that is really there: an empty or failed document has no
        // form to fill, and the attempts would spend themselves on nothing.
        if (view == null || !ready) return@LaunchedEffect
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
                            progressNotice(completion, page, autoFilled),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = retry,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("重新加载")
                    }
                }
                CompletionPanel(completion, page is PortalPageState.Failed, onClose)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { session.createView().also { view = it } },
                update = { }
            )
            when (val state = page) {
                is PortalPageState.Loading -> PageLoading(state.progress)
                is PortalPageState.Failed -> PageFailure(state.reason, retry)
                PortalPageState.Ready -> Unit
            }
        }
    }
}

/**
 * The line under the title; it is the only place that says what the page is still doing. The page
 * itself comes first: an outcome the app already knows would otherwise keep telling the user a page
 * that never arrived is still on its way.
 */
private fun progressNotice(completion: PortalCompletion, page: PortalPageState, autoFilled: Boolean): String = when {
    completion == PortalCompletion.Online -> "认证成功，正在返回首页。"
    // No auto-return here, so the line says what is true and leaves the choice to the user.
    completion == PortalCompletion.AlreadyOnline -> "这张 Wi-Fi 已经可以上网，若尚未登录请在本页完成。"
    page is PortalPageState.Failed -> "学校认证页未能打开。"
    page is PortalPageState.Loading -> "正在打开学校登录页…"
    autoFilled -> "已自动填写，正在提交认证。"
    completion is PortalCompletion.Manual -> completion.reason
    else -> "正在准备学校登录页，可手动完成。"
}

/**
 * The outcome sits above the page instead of replacing it: a manual login has to stay possible
 * while the watch is running and after it has given up. Each outcome is one status pill, one muted
 * detail line and, when there is one, one action, so the strip stays as short as the school page
 * underneath it needs.
 */
@Composable
private fun CompletionPanel(completion: PortalCompletion, pageFailed: Boolean, onClose: () -> Unit) {
    val palette = LocalCampusPalette.current
    when {
        completion == PortalCompletion.Online -> OutcomeRow(
            pill = "认证成功",
            ink = palette.success,
            wash = palette.successWash,
            detail = "校园网已可上网，即将自动返回首页。",
            trailing = {
                TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("完成") }
            }
        )
        // Already online before this page opened: the app has no evidence a login happened, so the
        // page stays put and the user decides. Auto-returning here is what closed the page before the
        // user could check anything or press anything.
        completion == PortalCompletion.AlreadyOnline -> OutcomeRow(
            pill = "网络可用",
            ink = palette.success,
            wash = palette.successWash,
            detail = "若尚未登录，请在本页完成后再返回。",
            trailing = {
                TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("完成") }
            }
        )
        // A page that never opened cannot be logged in by hand, so its own failure is the only thing
        // the user can act on and the manual invitation would be advice they cannot follow.
        pageFailed -> Unit
        completion is PortalCompletion.Manual -> OutcomeRow(
            pill = "未确认联网",
            ink = palette.warning,
            wash = palette.warningWash,
            detail = completion.reason
        )
    }
}

@Composable
private fun OutcomeRow(
    pill: String,
    ink: Color,
    wash: Color,
    detail: String,
    trailing: (@Composable () -> Unit)? = null
) {
    val palette = LocalCampusPalette.current
    Row(
        Modifier.fillMaxWidth().padding(start = CampusSpace.lg, end = CampusSpace.sm, top = CampusSpace.sm, bottom = CampusSpace.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CampusPill(pill, ink, wash)
        Spacer(Modifier.width(CampusSpace.md))
        Text(detail, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
        if (trailing != null) {
            Spacer(Modifier.width(CampusSpace.sm))
            trailing()
        }
    }
}

/** A page that is still coming: the spinner sits over it so a slow page still shows as it paints. */
@Composable
private fun PageLoading(progress: Int) {
    val palette = LocalCampusPalette.current
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.size(CampusSpace.md))
        CampusPill(
            if (progress in 1..99) "正在打开学校登录页… $progress%" else "正在打开学校登录页…",
            palette.onAccentWash,
            palette.accentWash
        )
    }
}

/**
 * A page that never arrived, on the soft canvas the rest of the app uses so the raised card still
 * reads as raised: the reason in plain words and the one action that can still change it.
 */
@Composable
private fun PageFailure(reason: String, onReload: () -> Unit) {
    val palette = LocalCampusPalette.current
    Box(Modifier.fillMaxSize().background(palette.canvas), contentAlignment = Alignment.Center) {
        CampusCard(Modifier.padding(CampusSpace.xxl)) {
            Column(
                Modifier.fillMaxWidth().padding(CampusSpace.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(CampusSpace.md)
            ) {
                CampusPill("学校认证页未能打开", palette.danger, palette.dangerWash)
                Icon(
                    Icons.Outlined.CloudOff,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    tint = palette.textTertiary
                )
                Text(
                    reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary,
                    textAlign = TextAlign.Center
                )
                TextButton(onClick = onReload, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("重新加载")
                }
            }
        }
    }
}
