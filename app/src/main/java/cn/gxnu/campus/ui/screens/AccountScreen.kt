package cn.gxnu.campus.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cn.gxnu.campus.ui.CampusActions
import cn.gxnu.campus.ui.CampusUiState
import cn.gxnu.campus.ui.common.CampusCard
import cn.gxnu.campus.ui.common.CampusPageHeader
import cn.gxnu.campus.ui.common.CampusPrimaryButton
import cn.gxnu.campus.ui.common.CampusSwitch
import cn.gxnu.campus.ui.common.DeleteAccountDialog
import cn.gxnu.campus.ui.common.OfficialPortalLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun AccountScreen(
    state: CampusUiState,
    actions: CampusActions,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Credentials stay in memory and never enter saved instance state or preview preferences.
    val account = remember { mutableStateOf("") }
    val password = remember { mutableStateOf("") }
    var rememberAccount by remember { mutableStateOf(if (state.accountConfigured) state.accountSaved else true) }
    var submitted by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    val passwordFocus = remember { FocusRequester() }
    val saveScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val fontScale = LocalDensity.current.fontScale
    // Only error transitions invalidate the form. Character edits are observed by each field.
    val accountError by remember {
        derivedStateOf(structuralEqualityPolicy()) { submitted && account.value.isBlank() }
    }
    val passwordError by remember {
        derivedStateOf(structuralEqualityPolicy()) { submitted && password.value.isEmpty() }
    }
    val busy = saving || state.accountSaving || state.initializing
    val accountKeyboardActions = remember(passwordFocus) {
        KeyboardActions(onNext = { passwordFocus.requestFocus() })
    }
    // Built here, not per keystroke: the fields recompose on every character, and Material3's
    // colors() builds a fresh TextFieldColors on each call.
    val fieldColors = accountFieldColors()

    fun save() {
        if (busy) return
        submitted = true
        saveError = null
        val submittedAccount = account.value.trim()
        val submittedPassword = password.value
        if (submittedAccount.isEmpty() || submittedPassword.isEmpty()) return
        val rememberRequested = rememberAccount
        saving = true
        saveScope.launch {
            try {
                if (actions.saveAccount(submittedAccount, submittedPassword, rememberRequested)) {
                    password.value = ""
                    focusManager.clearFocus()
                    onSaved()
                } else {
                    saveError = "未能保存账号，请重试。你的输入已保留。"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                saveError = "未能保存账号，请重试。你的输入已保留。"
            } finally {
                saving = false
            }
        }
    }

    // These are handed to Material3 text fields, which already recompose on every character.
    // Rebuilding them here would additionally let any unrelated app-state update that arrives
    // mid-typing recompose both fields in full.
    val clearSaveError = remember { { saveError = null } }
    val currentSave by rememberUpdatedState { save() }
    val passwordKeyboardActions = remember { KeyboardActions(onDone = { currentSave() }) }
    val accountFieldModifier = remember {
        Modifier.fillMaxWidth().testTag("account_input").semantics { contentDescription = "校园账号" }
    }
    val passwordFieldModifier = remember(passwordFocus) {
        Modifier.fillMaxWidth().focusRequester(passwordFocus).testTag("password_input")
            .semantics { contentDescription = "校园网密码" }
    }

    Column(
        modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CampusPageHeader(
            title = "校园账号",
            subtitle = if (state.accountConfigured) "当前账号：${state.maskedAccount}" else "填写学校校园网账号和密码",
            leading = {
                IconButton(onClick = onBack, enabled = !busy, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回上一页", modifier = Modifier.size(22.dp))
                }
            }
        )
        CampusCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("校园账号", style = MaterialTheme.typography.bodyMedium)
                    AccountInputField(
                        text = account,
                        enabled = !busy,
                        onEdited = clearSaveError,
                        placeholder = "输入校园网账号",
                        isError = accountError,
                        passwordField = false,
                        keyboardActions = accountKeyboardActions,
                        colors = fieldColors,
                        modifier = accountFieldModifier
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("密码", style = MaterialTheme.typography.bodyMedium)
                    AccountInputField(
                        text = password,
                        enabled = !busy,
                        onEdited = clearSaveError,
                        placeholder = if (state.accountConfigured) "重新输入校园网密码" else "输入校园网密码",
                        isError = passwordError,
                        passwordField = true,
                        keyboardActions = passwordKeyboardActions,
                        colors = fieldColors,
                        modifier = passwordFieldModifier
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("保存登录信息", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (rememberAccount) "加密保存在此设备，可随时删除。" else "仅在本次会话使用，重开时需填写。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    CampusSwitch(rememberAccount, { rememberAccount = it }, label = "保存登录信息到此设备", enabled = !busy)
                }
                if (saveError != null) {
                    Text(
                        saveError!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                CampusPrimaryButton(
                    title = when {
                        state.initializing -> "正在读取设置…"
                        busy -> "正在保存…"
                        rememberAccount -> "保存账号"
                        else -> "使用此账号"
                    },
                    onClick = { save() },
                    busy = busy,
                    modifier = Modifier.testTag("save_account")
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("账号仅用于校园网认证", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 320.dp || fontScale > 1.35f) {
                Column {
                    TextButton(onClick = onBack, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (state.accountConfigured) "取消修改" else "稍后设置", style = MaterialTheme.typography.bodyMedium)
                    }
                    OfficialPortalLink(actions)
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (state.accountConfigured) "取消修改" else "稍后设置", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.weight(1f))
                    OfficialPortalLink(actions)
                }
            }
        }
        if (state.accountConfigured) {
            TextButton(onClick = { showDelete = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("删除此设备的账号", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
    if (showDelete) {
        DeleteAccountDialog(
            onDismiss = { showDelete = false },
            onDelete = {
                actions.deleteAccount()
                account.value = ""
                password.value = ""
                rememberAccount = true
                submitted = false
                saveError = null
                showDelete = false
            }
        )
    }
}

@Composable
private fun AccountInputField(
    text: MutableState<String>,
    enabled: Boolean,
    onEdited: () -> Unit,
    placeholder: String,
    isError: Boolean,
    passwordField: Boolean,
    keyboardActions: KeyboardActions,
    colors: TextFieldColors,
    modifier: Modifier = Modifier
) {
    // Keep text/visibility reads in this restart scope, away from the surrounding form.
    // Updates remain synchronous so IME composition, selection, and paste stay intact.
    var passwordVisible by remember { mutableStateOf(false) }
    val passwordTransformation = remember { PasswordVisualTransformation() }
    val keyboardOptions = remember(passwordField) {
        KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = if (passwordField) KeyboardType.Password else KeyboardType.Text,
            imeAction = if (passwordField) ImeAction.Done else ImeAction.Next
        )
    }
    OutlinedTextField(
        value = text.value,
        onValueChange = {
            text.value = it
            onEdited()
        },
        enabled = enabled,
        textStyle = MaterialTheme.typography.bodyMedium,
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
        isError = isError,
        singleLine = true,
        supportingText = if (isError) ({ Text(if (passwordField) "请填写密码" else "请填写校园账号") }) else null,
        visualTransformation = if (passwordField && !passwordVisible) passwordTransformation else VisualTransformation.None,
        trailingIcon = if (passwordField) ({
            IconButton(onClick = { passwordVisible = !passwordVisible }, enabled = enabled, modifier = Modifier.size(48.dp)) {
                Icon(
                    if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }) else null,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        shape = MaterialTheme.shapes.medium,
        colors = colors,
        modifier = modifier
    )
}

@Composable
private fun accountFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    errorContainerColor = MaterialTheme.colorScheme.errorContainer,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant
)
