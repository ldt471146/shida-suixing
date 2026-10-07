package cn.gxnu.campus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.gxnu.campus.core.CampusRoute
import cn.gxnu.campus.core.Provider
import cn.gxnu.campus.ui.common.CampusNoticeStrip
import cn.gxnu.campus.ui.common.CampusRow
import cn.gxnu.campus.ui.common.CampusRowDivider
import cn.gxnu.campus.ui.common.rememberEntranceGate
import cn.gxnu.campus.ui.screens.AccountScreen
import cn.gxnu.campus.ui.screens.CampusNetworkScreen
import cn.gxnu.campus.ui.screens.HomeScreen
import cn.gxnu.campus.ui.screens.ProfileScreen
import cn.gxnu.campus.ui.screens.ServicesScreen
import cn.gxnu.campus.ui.screens.SettingsScreen
import cn.gxnu.campus.ui.screens.TimetableScreen
import cn.gxnu.campus.ui.theme.CampusRadius
import cn.gxnu.campus.ui.theme.CampusSpace
import cn.gxnu.campus.ui.theme.CampusTheme
import cn.gxnu.campus.ui.theme.LocalCampusPalette

/**
 * 应用外壳：三个 tab（首页 / 功能 / 我的）加它们下面的二级页。
 *
 * 打开应用落在首页 —— 校园网是要在「功能」里点开才进的一页，不再是进门就撞上的东西。页面之间
 * 的转场由 [campusTransition] 决定方向：往右切 tab 就从右边进，进二级页从右边推入，返回时
 * 反过来。每一页第一次出现时条目会错峰淡入，之后无论怎么切、怎么滚都不再重播。
 */
@Composable
fun CampusApp(
    state: CampusUiState,
    actions: CampusActions,
    updates: UpdateActions? = null,
    updateState: UpdateUiState = UpdateUiState()
) {
    CampusTheme(state.theme) {
        var destinationName by rememberSaveable { mutableStateOf(Destination.HOME.name) }
        var initialRouteResolved by rememberSaveable { mutableStateOf(false) }
        var accountReturnName by rememberSaveable { mutableStateOf(Destination.HOME.name) }
        var showProvider by remember { mutableStateOf(false) }
        val snackbar = remember { SnackbarHostState() }
        val currentActions by rememberUpdatedState(actions)
        val gate = rememberEntranceGate()
        // 保存的是名字：一次升级后枚举里少了一个名字不该让应用起不来。
        val destination = Destination.entries.firstOrNull { it.name == destinationName } ?: Destination.HOME
        val accountReturn = Destination.entries.firstOrNull { it.name == accountReturnName } ?: Destination.HOME
        // The controller outlives the timetable destination on purpose: a recognition that is
        // already in flight still finishes and persists when the user navigates away.
        val timetable = rememberTimetableController()
        val timetableState by timetable.state.collectAsStateWithLifecycle()

        fun openAccount() {
            accountReturnName = destinationName
            destinationName = Destination.ACCOUNT.name
        }

        fun goBack() {
            if (!state.accountSaving) destinationName = destinationParent(destination, accountReturn).name
        }

        fun openRoute(route: CampusRoute) {
            initialRouteResolved = true
            destinationName = destinationOf(route).name
        }

        LaunchedEffect(state.initializing, state.isPreview) {
            if (!state.initializing && !initialRouteResolved) {
                initialRouteResolved = true
                if (!state.accountConfigured && !state.isPreview && destination == Destination.HOME) {
                    accountReturnName = Destination.HOME.name
                    destinationName = Destination.ACCOUNT.name
                }
            }
        }
        LaunchedEffect(state.feedbackId) {
            val message = state.feedback
            val eventId = state.feedbackId
            if (!message.isNullOrBlank()) {
                snackbar.showSnackbar(message, duration = SnackbarDuration.Short, withDismissAction = true)
                currentActions.clearFeedback(eventId)
            }
        }
        LaunchedEffect(updateState.messageId) {
            val message = updateState.message
            val eventId = updateState.messageId
            if (!message.isNullOrBlank()) {
                snackbar.showSnackbar(message, duration = SnackbarDuration.Short, withDismissAction = true)
                updates?.clearUpdateMessage(eventId)
            }
        }
        // The sheets own their own back handling, so this only runs for a real page below a tab.
        BackHandler(enabled = destination != Destination.HOME && !showProvider) { goBack() }

        Scaffold(
            containerColor = LocalCampusPalette.current.canvas,
            contentColor = MaterialTheme.colorScheme.onBackground,
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                CampusNavigationBar(
                    destination = destination,
                    accountReturn = accountReturn,
                    enabled = !state.initializing && !state.accountSaving
                ) {
                    initialRouteResolved = true
                    destinationName = it.name
                }
            }
        ) { padding ->
            Box(
                Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(Modifier.widthIn(max = 680.dp).fillMaxSize()) {
                    if (state.isPreview) {
                        CampusNoticeStrip(
                            "界面预览 · 未执行校园认证",
                            Modifier.padding(horizontal = CampusSpace.lg, vertical = CampusSpace.sm)
                        )
                    }
                    AnimatedContent(
                        targetState = destination,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        transitionSpec = { campusTransition(accountReturn) },
                        label = "campus_page"
                    ) { page ->
                        val pageModifier = Modifier.fillMaxSize()
                        when (page) {
                            Destination.HOME -> HomeScreen(
                                timetableState = timetableState,
                                gate = gate,
                                onRoute = ::openRoute,
                                modifier = pageModifier
                            )
                            Destination.SERVICES -> ServicesScreen(
                                gate = gate,
                                onRoute = ::openRoute,
                                modifier = pageModifier
                            )
                            Destination.CAMPUS_NETWORK -> CampusNetworkScreen(
                                state = state,
                                actions = actions,
                                onBack = ::goBack,
                                onAccount = ::openAccount,
                                onProvider = { showProvider = true },
                                gate = gate,
                                modifier = pageModifier
                            )
                            Destination.TIMETABLE -> TimetableScreen(
                                state = timetableState,
                                actions = timetable,
                                gate = gate,
                                onBack = ::goBack,
                                modifier = pageModifier
                            )
                            Destination.PROFILE -> ProfileScreen(
                                state = state,
                                actions = actions,
                                gate = gate,
                                onAccount = ::openAccount,
                                onSettings = { destinationName = Destination.SETTINGS.name },
                                modifier = pageModifier
                            )
                            Destination.SETTINGS -> SettingsScreen(
                                state = state,
                                actions = actions,
                                updates = updates,
                                updateState = updateState,
                                gate = gate,
                                onBack = ::goBack,
                                modifier = pageModifier
                            )
                            Destination.ACCOUNT -> AccountScreen(
                                state = state,
                                actions = actions,
                                onBack = ::goBack,
                                onSaved = ::goBack,
                                modifier = pageModifier
                            )
                        }
                    }
                }
            }
        }
        if (showProvider) ProviderSheet(
            selected = state.selectedProvider,
            onDismiss = { showProvider = false },
            onSelect = {
                actions.selectProvider(it)
                showProvider = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderSheet(selected: Provider?, onDismiss: () -> Unit, onSelect: (Provider) -> Unit) {
    val palette = LocalCampusPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = CampusRadius.lgShape,
        containerColor = palette.surface,
        contentColor = palette.textPrimary
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(bottom = CampusSpace.xxl),
            verticalArrangement = Arrangement.spacedBy(CampusSpace.md)
        ) {
            Column(
                Modifier.padding(horizontal = CampusSpace.lg),
                verticalArrangement = Arrangement.spacedBy(CampusSpace.xs)
            ) {
                Text(
                    "选择运营商",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() }
                )
                Text(
                    "按学校认证页面选择，下次沿用本次设置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textTertiary
                )
            }
            Column(Modifier.selectableGroup()) {
                Provider.entries.forEachIndexed { index, provider ->
                    val isSelected = selected == provider
                    CampusRow(
                        title = provider.title,
                        onClick = { onSelect(provider) },
                        showChevron = false,
                        trailing = {
                            RadioButton(
                                selected = isSelected,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = palette.accent,
                                    unselectedColor = palette.textTertiary
                                )
                            )
                        }
                    )
                    if (index < Provider.entries.lastIndex) CampusRowDivider()
                }
            }
        }
    }
}
