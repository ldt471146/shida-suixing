package cn.gxnu.campus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.gxnu.campus.core.Provider
import cn.gxnu.campus.ui.common.CampusNoticeStrip
import cn.gxnu.campus.ui.common.CampusRow
import cn.gxnu.campus.ui.common.CampusRowDivider
import cn.gxnu.campus.ui.screens.AccountScreen
import cn.gxnu.campus.ui.screens.HomeScreen
import cn.gxnu.campus.ui.screens.ProfileScreen
import cn.gxnu.campus.ui.screens.ServicesScreen
import cn.gxnu.campus.ui.screens.TimetableScreen
import cn.gxnu.campus.ui.theme.CampusRadius
import cn.gxnu.campus.ui.theme.CampusSpace
import cn.gxnu.campus.ui.theme.CampusTheme
import cn.gxnu.campus.ui.theme.LocalCampusPalette

private enum class Destination(val title: String, val icon: ImageVector) {
    HOME("首页", Icons.Outlined.Home),
    SERVICES("服务", Icons.Outlined.GridView),
    TIMETABLE("课表", Icons.Outlined.Schedule),
    PROFILE("我的", Icons.Outlined.PersonOutline),
    ACCOUNT("校园账号", Icons.Outlined.PersonOutline)
}

@Composable
fun CampusApp(
    state: CampusUiState,
    actions: CampusActions,
    updates: UpdateActions? = null,
    updateState: UpdateUiState = UpdateUiState()
) {
    CampusTheme(state.theme) {
        var destinationName by rememberSaveable {
            mutableStateOf(Destination.HOME.name)
        }
        var initialRouteResolved by rememberSaveable { mutableStateOf(false) }
        var accountReturnName by rememberSaveable { mutableStateOf(Destination.HOME.name) }
        var showProvider by remember { mutableStateOf(false) }
        val snackbar = remember { SnackbarHostState() }
        val currentActions by rememberUpdatedState(actions)
        val destination = Destination.valueOf(destinationName)
        // The controller outlives the timetable destination on purpose: a recognition that is
        // already in flight still finishes and persists when the user navigates away.
        val timetable = rememberTimetableController()
        val timetableState by timetable.state.collectAsStateWithLifecycle()

        fun openAccount() {
            accountReturnName = destinationName
            destinationName = Destination.ACCOUNT.name
        }

        LaunchedEffect(state.initializing, state.isPreview) {
            if (!state.initializing && !initialRouteResolved) {
                initialRouteResolved = true
                if (!state.accountConfigured && !state.isPreview && destinationName == Destination.HOME.name) {
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
        BackHandler(enabled = destination != Destination.HOME && !showProvider) {
            if (!state.accountSaving) {
                destinationName = when (destination) {
                    Destination.ACCOUNT -> accountReturnName
                    // 课表 is entered from 服务, so returning there is less disorienting than 首页.
                    Destination.TIMETABLE -> Destination.SERVICES.name
                    else -> Destination.HOME.name
                }
            }
        }

        Scaffold(
            containerColor = LocalCampusPalette.current.canvas,
            contentColor = MaterialTheme.colorScheme.onBackground,
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                CampusNavigation(destination, enabled = !state.initializing && !state.accountSaving) {
                    initialRouteResolved = true
                    destinationName = it.name
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 680.dp).fillMaxSize()) {
                    if (state.isPreview) {
                        CampusNoticeStrip(
                            "界面预览 · 未执行校园认证",
                            Modifier.padding(horizontal = CampusSpace.lg, vertical = CampusSpace.sm)
                        )
                    }
                    val screenModifier = Modifier.fillMaxWidth().weight(1f)
                    when (destination) {
                        Destination.HOME -> HomeScreen(
                            state, actions, ::openAccount, { showProvider = true }, updates, updateState, screenModifier
                        )
                        Destination.SERVICES -> ServicesScreen(
                            state,
                            onCampusNetwork = { destinationName = Destination.HOME.name },
                            onTimetable = { destinationName = Destination.TIMETABLE.name },
                            modifier = screenModifier
                        )
                        Destination.TIMETABLE -> TimetableScreen(timetableState, timetable, screenModifier)
                        Destination.PROFILE -> ProfileScreen(
                            state, actions, ::openAccount, updates, updateState, screenModifier
                        )
                        Destination.ACCOUNT -> AccountScreen(
                            state, actions,
                            onBack = { destinationName = accountReturnName },
                            onSaved = { destinationName = Destination.HOME.name },
                            modifier = screenModifier
                        )
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

/** Bottom navigation keeps the shell's nav language: wash + accent for the active tab. */
@Composable
private fun CampusNavigation(destination: Destination, enabled: Boolean, onSelect: (Destination) -> Unit) {
    val palette = LocalCampusPalette.current
    Surface(color = palette.chrome) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
            HorizontalDivider(color = palette.border)
            Row(
                Modifier.widthIn(max = 680.dp).fillMaxWidth().selectableGroup()
                    .padding(horizontal = CampusSpace.md, vertical = CampusSpace.sm),
                horizontalArrangement = Arrangement.spacedBy(CampusSpace.sm)
            ) {
                listOf(Destination.HOME, Destination.SERVICES, Destination.PROFILE).forEach { item ->
                    // 课表 is reached from 服务, so it keeps that tab lit instead of clearing the bar.
                    val selected = destination == item ||
                        destination == Destination.ACCOUNT && item == Destination.PROFILE ||
                        destination == Destination.TIMETABLE && item == Destination.SERVICES
                    val ink = when {
                        !enabled -> palette.textTertiary
                        selected -> palette.onAccentWash
                        else -> palette.textSecondary
                    }
                    Surface(
                        modifier = Modifier.weight(1f)
                            .selectable(selected, enabled = enabled, role = Role.Tab, onClick = { onSelect(item) }),
                        shape = CampusRadius.mdShape,
                        color = Color.Transparent
                    ) {
                        Column(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = CampusSpace.xs),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            // The indicator wraps the icon on purpose: a full-width wash would
                            // make one third of the bar shout.
                            Surface(
                                modifier = Modifier.size(width = 64.dp, height = 30.dp),
                                shape = CampusRadius.pillShape,
                                color = if (selected && enabled) palette.selected else Color.Transparent
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(item.icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = ink)
                                }
                            }
                            Text(item.title, color = ink, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
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
