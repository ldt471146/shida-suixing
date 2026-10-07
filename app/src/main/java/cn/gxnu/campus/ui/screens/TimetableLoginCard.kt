package cn.gxnu.campus.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cn.gxnu.campus.ui.common.CampusCard
import cn.gxnu.campus.ui.common.CampusIconPlate
import cn.gxnu.campus.ui.common.CampusPrimaryButton
import cn.gxnu.campus.ui.common.CampusSwitch
import cn.gxnu.campus.ui.theme.CampusSpace
import cn.gxnu.campus.ui.theme.LocalCampusPalette

// ---------------------------------------------------------------------------------------------
// Login and state cards
// ---------------------------------------------------------------------------------------------

/** 登录研究生系统: 学号 + 密码换一枚 token，课表从研究生系统直接取回本机。 */
@Composable
internal fun LoginCard(
    account: String,
    password: String,
    onAccountChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    remember: Boolean,
    onRememberChange: (Boolean) -> Unit,
    accountMissing: Boolean,
    passwordMissing: Boolean,
    signingIn: Boolean,
    onSubmit: () -> Unit
) {
    val palette = LocalCampusPalette.current
    // The password field's own state, so toggling it never restarts the form around it.
    var passwordVisible by remember { mutableStateOf(false) }
    val passwordTransformation = remember { PasswordVisualTransformation() }
    val accountError: (@Composable () -> Unit)? = if (accountMissing) ({
        Text("请输入学号。", style = MaterialTheme.typography.bodySmall)
    }) else null
    val passwordError: (@Composable () -> Unit)? = if (passwordMissing) ({
        Text("请输入密码。", style = MaterialTheme.typography.bodySmall)
    }) else null
    CampusCard {
        Column(Modifier.padding(CampusSpace.lg), verticalArrangement = Arrangement.spacedBy(CampusSpace.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CampusIconPlate(Icons.Outlined.Schedule, plate = palette.accentWash, tint = palette.onAccentWash)
                Spacer(Modifier.width(CampusSpace.md))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "登录研究生系统",
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.textPrimary,
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(
                        "课表从研究生系统直接取回，需要连上校园网。",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textTertiary
                    )
                }
            }
            OutlinedTextField(
                value = account,
                onValueChange = onAccountChange,
                enabled = !signingIn,
                label = { Text("学号") },
                placeholder = { Text("输入研究生系统学号") },
                supportingText = accountError,
                isError = accountMissing,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                colors = courseFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                enabled = !signingIn,
                label = { Text("密码") },
                placeholder = { Text("输入研究生系统密码") },
                supportingText = passwordError,
                isError = passwordMissing,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                visualTransformation = if (passwordVisible) VisualTransformation.None else passwordTransformation,
                trailingIcon = {
                    IconButton(
                        onClick = { passwordVisible = !passwordVisible },
                        enabled = !signingIn,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                            modifier = Modifier.size(22.dp),
                            tint = palette.textSecondary
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                colors = courseFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("记住本机", style = MaterialTheme.typography.bodyMedium, color = palette.textPrimary)
                    Text(
                        if (remember) "学号加密保存在这台手机上，下次打开自动续上登录。"
                        else "只在本次使用，下次打开需要重新登录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textTertiary
                    )
                }
                Spacer(Modifier.width(CampusSpace.sm))
                CampusSwitch(remember, onRememberChange, label = "记住本机", enabled = !signingIn)
            }
            CampusPrimaryButton(
                title = if (signingIn) "正在登录…" else "登录并获取课表",
                onClick = onSubmit,
                busy = signingIn,
                showProgress = false
            )
        }
    }
}

/** 已经登录，但这一次没有取回课表: 说清楚现在的状态，再给一个重新取回的按钮。 */
@Composable
internal fun NoTimetableCard(signingIn: Boolean, onRefresh: () -> Unit) {
    val palette = LocalCampusPalette.current
    CampusCard {
        Column(Modifier.padding(CampusSpace.lg), verticalArrangement = Arrangement.spacedBy(CampusSpace.md)) {
            Text(
                "还没有取到课表",
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                "本机没有保存课表。确认已经连上校园网，再更新一次。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textTertiary
            )
            CampusPrimaryButton(
                title = if (signingIn) "正在更新…" else "更新课表",
                onClick = onRefresh,
                busy = signingIn,
                showProgress = false
            )
        }
    }
}
