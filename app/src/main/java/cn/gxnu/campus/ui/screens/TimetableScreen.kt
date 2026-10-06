package cn.gxnu.campus.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import cn.gxnu.campus.core.TIMETABLE_WEEKDAYS
import cn.gxnu.campus.core.Timetable
import cn.gxnu.campus.core.TimetableBlock
import cn.gxnu.campus.core.TimetableCourse
import cn.gxnu.campus.core.TimetableDay
import cn.gxnu.campus.core.TimetableGridLayout
import cn.gxnu.campus.ui.TimetableActions
import cn.gxnu.campus.ui.TimetableUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val GridCellHeight = 66.dp
private val GridCellGap = 4.dp
private val GridDayWidth = 96.dp
private val GridPeriodWidth = 32.dp
private val GridHeaderHeight = 30.dp
private val GridShape = RoundedCornerShape(8.dp)

/**
 * 课表: pick or shoot a timetable photo, recognise it through the vision API, and show the result as
 * a weekday × period grid. Stateless apart from the picker plumbing, so the host owns all state.
 */
@Composable
fun TimetableScreen(
    state: TimetableUiState,
    actions: TimetableActions,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The key draft stays out of saved state on purpose: it is a secret, not form data to restore.
    var keyDraft by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var captureTarget by remember { mutableStateOf<Uri?>(null) }
    val colors = MaterialTheme.colorScheme

    fun load(uri: Uri, discardAfterwards: Boolean) {
        if (preparing) return
        preparing = true
        notice = null
        scope.launch {
            when (val result = loadPreparedImage(context, uri, discardAfterwards)) {
                is ImageLoad.Ready -> {
                    preview = result.prepared.preview
                    actions.useImage(result.prepared.image)
                }
                is ImageLoad.Failed -> notice = result.message
            }
            preparing = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) load(uri, discardAfterwards = false) else notice = "没有选择图片。"
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val target = captureTarget
        captureTarget = null
        if (captured && target != null) load(target, discardAfterwards = true)
        else {
            target?.let { TimetableImageLoader.discardCaptureTarget(context, it) }
            notice = "已取消拍照。"
        }
    }

    val message = state.message ?: notice
    val captureSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("课表", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                Text(
                    "选择或拍摄课表照片，AI 识别后在本机生成周课表；重新识别即可替换。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
        item {
            ApiKeySection(
                keyConfigured = state.keyConfigured,
                keyHint = state.keyHint,
                draft = keyDraft,
                onDraftChange = { keyDraft = it },
                visible = keyVisible,
                onVisibleChange = { keyVisible = it },
                onSave = {
                    actions.saveApiKey(keyDraft)
                    keyDraft = ""
                },
                onClear = { actions.clearApiKey() }
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = !preparing && !state.recognizing,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("选择图片")
                    }
                    if (captureSupported) {
                        OutlinedButton(
                            onClick = {
                                val target = TimetableImageLoader.createCaptureTarget(context)
                                if (target == null) {
                                    notice = "无法创建拍照任务，请改用相册里的照片。"
                                } else {
                                    captureTarget = target
                                    try {
                                        camera.launch(target)
                                    } catch (_: Exception) {
                                        captureTarget = null
                                        TimetableImageLoader.discardCaptureTarget(context, target)
                                        notice = "这台设备没有可用的相机应用。"
                                    }
                                }
                            },
                            enabled = !preparing && !state.recognizing,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Outlined.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("拍照")
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { actions.retry() },
                        enabled = state.canRetry && !state.recognizing && !preparing,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        if (state.recognizing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = colors.onPrimary
                            )
                        } else {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(if (state.timetable == null) "开始识别" else "重新识别")
                    }
                    if (state.timetable != null) {
                        TextButton(
                            onClick = { confirmingDelete = true },
                            enabled = !state.recognizing,
                            colors = ButtonDefaults.textButtonColors(contentColor = colors.error),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("删除课表")
                        }
                    }
                }
                if (preparing) {
                    StatusLine("正在压缩图片…")
                } else if (state.recognizing) {
                    StatusLine("正在识别课表，通常需要几秒到十几秒…")
                }
            }
        }
        preview?.let { bitmap ->
            item {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = colors.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("待识别的图片", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                        Image(
                            bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(140.dp).background(colors.surface, GridShape)
                        )
                    }
                }
            }
        }
        if (!message.isNullOrBlank()) {
            item {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = if (state.failure != null) colors.errorContainer else colors.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.failure != null) colors.onErrorContainer else colors.onSecondaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            notice = null
                            actions.clearMessage(state.messageId)
                        }) {
                            Icon(Icons.Outlined.Close, contentDescription = "关闭提示", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
        item {
            when {
                state.restoring -> StatusLine("正在读取本机保存的课表…")
                state.timetable != null -> TimetableContent(state.timetable)
                state.recognizing -> Unit
                else -> EmptyTimetable()
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("删除本机课表？") },
            text = { Text("只会删除这台手机上保存的课表，图片和识别结果不会被上传到别处。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    preview = null
                    actions.deleteTimetable()
                }) { Text("删除", color = colors.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ApiKeySection(
    keyConfigured: Boolean,
    keyHint: String,
    draft: String,
    onDraftChange: (String) -> Unit,
    visible: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.medium, color = colors.surfaceVariant.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Key, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("识别服务", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (keyConfigured) {
                    TextButton(onClick = onClear) {
                        Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("删除 Key")
                    }
                }
            }
            Text(
                "API Key 使用 Android Keystore 加密保存在本机，不会写进源码或安装包；识别时只有课表图片会发送到识别服务。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
            if (keyConfigured) {
                Text("已保存：$keyHint", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                singleLine = true,
                label = { Text(if (keyConfigured) "更换 API Key" else "API Key") },
                placeholder = { Text("sk-…") },
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { onVisibleChange(!visible) }) {
                        Icon(
                            if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (visible) "隐藏 API Key" else "显示 API Key",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = onSave, enabled = draft.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (keyConfigured) "保存新的 API Key" else "保存到本机")
            }
        }
    }
}

@Composable
private fun StatusLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyTimetable() {
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.medium, color = colors.surfaceVariant.copy(alpha = 0.35f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("还没有课表", style = MaterialTheme.typography.titleMedium)
            Text(
                "填写 API Key 后选择或拍摄课表照片，识别成功后这里会显示周课表，并保存在本机。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
            Text(
                "课表照片越清晰、越正对，识别越准确；看不清的课程不会凭空补全。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TimetableContent(timetable: Timetable) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (timetable.term.isBlank()) "共 ${timetable.courseCount} 门课程" else "${timetable.term} · ${timetable.courseCount} 门课程",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                "识别于 ${android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", timetable.recognizedAtMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
        WeeklyGrid(timetable)
    }
}

@Composable
private fun WeeklyGrid(timetable: Timetable) {
    val grid = remember(timetable) { TimetableGridLayout.build(timetable) }
    val horizontal = rememberScrollState()
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(Modifier.horizontalScroll(horizontal).padding(bottom = GridCellGap)) {
            Spacer(Modifier.width(GridPeriodWidth))
            grid.days.forEach { day ->
                Spacer(Modifier.width(GridCellGap))
                Box(
                    Modifier.width(GridDayWidth).height(GridHeaderHeight)
                        .background(MaterialTheme.colorScheme.secondaryContainer, GridShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        TIMETABLE_WEEKDAYS[day.weekday - 1],
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
        Row(Modifier.horizontalScroll(horizontal)) {
            Column(Modifier.width(GridPeriodWidth), verticalArrangement = Arrangement.spacedBy(GridCellGap)) {
                grid.periods.forEach { period ->
                    Box(
                        Modifier.fillMaxWidth().height(GridCellHeight),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "$period",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            grid.days.forEach { day ->
                Spacer(Modifier.width(GridCellGap))
                DayColumn(day)
            }
        }
    }
}

@Composable
private fun DayColumn(day: TimetableDay) {
    Column(Modifier.width(GridDayWidth), verticalArrangement = Arrangement.spacedBy(GridCellGap)) {
        day.blocks.forEach { block ->
            when (block) {
                is TimetableBlock.Course -> CourseCell(
                    block.course,
                    Modifier.fillMaxWidth().height(blockHeight(block.span))
                )
                is TimetableBlock.Free -> Box(
                    Modifier.fillMaxWidth().height(GridCellHeight)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), GridShape)
                )
            }
        }
    }
}

/** A course spanning n periods occupies exactly n rows plus the gaps it covers. */
private fun blockHeight(span: Int): Dp = GridCellHeight * span + GridCellGap * (span - 1)

@Composable
private fun CourseCell(course: TimetableCourse, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    // The same course keeps the same tint while the timetable is unchanged.
    val warm = course.name.hashCode() % 2 == 0
    val container = if (warm) colors.primaryContainer else colors.tertiaryContainer
    val ink = if (warm) colors.onPrimaryContainer else colors.onTertiaryContainer
    Column(
        modifier.background(container, GridShape).padding(horizontal = 6.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Text(
            course.name,
            style = MaterialTheme.typography.labelMedium,
            color = ink,
            maxLines = if (course.periodSpan > 1) 2 else 1,
            overflow = TextOverflow.Ellipsis
        )
        if (course.detailLabel.isNotBlank()) {
            Text(
                course.detailLabel,
                style = MaterialTheme.typography.labelSmall,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            course.weekLabel,
            style = MaterialTheme.typography.labelSmall,
            color = ink.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private sealed interface ImageLoad {
    data class Ready(val prepared: PreparedTimetableImage) : ImageLoad
    data class Failed(val message: String) : ImageLoad
}

/** Decoding is blocking and memory hungry, so it runs off the main thread and never throws out. */
private suspend fun loadPreparedImage(context: Context, uri: Uri, discardAfterwards: Boolean): ImageLoad =
    withContext(Dispatchers.IO) {
        try {
            ImageLoad.Ready(TimetableImageLoader.prepare(context.contentResolver, uri))
        } catch (_: OutOfMemoryError) {
            ImageLoad.Failed("这张图片太大，手机无法处理，请换一张分辨率低一些的照片。")
        } catch (failure: ImagePreparationException) {
            ImageLoad.Failed(failure.message ?: "这张图片无法读取，请换一张试试。")
        } catch (_: Exception) {
            ImageLoad.Failed("这张图片无法读取，请换一张试试。")
        } finally {
            if (discardAfterwards) TimetableImageLoader.discardCaptureTarget(context, uri)
        }
    }
