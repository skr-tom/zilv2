@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.zilu.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.zilu.app.ui.CalendarScreen
import com.zilu.app.ui.ColorPicker
import com.zilu.app.ui.DateWheel
import com.zilu.app.ui.FocusScreen
import com.zilu.app.ui.FolderPalette
import com.zilu.app.ui.MonthDayFields
import com.zilu.app.ui.NotesScreen
import com.zilu.app.ui.PlansScreen
import com.zilu.app.ui.SettingsScreen
import com.zilu.app.ui.TasksScreen
import com.zilu.app.ui.TimeFields
import com.zilu.app.ui.TodoPalette
import com.zilu.app.ui.weekName
import com.zilu.app.ui.ziluColors
import kotlinx.coroutines.delay
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifier.ensureChannels(this)
        setContent { ZiluApp() }
    }
}

@Composable
private fun ZiluApp() {
    val context = LocalContext.current
    val controller = remember { ZiluController(context.applicationContext) }
    val state = controller.state
    val density = LocalDensity.current

    var page by rememberSaveable { mutableIntStateOf(0) }
    var showTaskDialog by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<DailyTask?>(null) }
    var todoDialogDate by remember { mutableStateOf<LocalDate?>(null) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var planDialogFolder by remember { mutableStateOf<String?>(null) }
    var subPlanTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var calendarDate by remember { mutableStateOf(LocalDate.now()) }

    val missingPermission = Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    var showPermissionPrompt by remember { mutableStateOf(missingPermission) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        showPermissionPrompt = false
    }

    LaunchedEffect(Unit) { ReminderScheduler.rescheduleAll(context) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            controller.refresh()
            controller.syncFocusState()
        }
    }

    val focus = state.activeFocus
    val tabs = listOf(
        "任务" to Icons.Default.Today,
        "计划" to Icons.Default.Flag,
        "日历" to Icons.Default.CalendarMonth,
        "小记" to Icons.Default.EditNote,
        "设置" to Icons.Default.Settings
    )

    CompositionLocalProvider(
        LocalDensity provides Density(density.density, state.settings.fontScale)
    ) {
        MaterialTheme(colorScheme = ziluColors(state.settings.darkMode, state.settings.themeName)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                if (focus != null) {
                    FocusScreen(session = focus, onExit = { controller.exitFocus() })
                } else {
                    Scaffold(
                        containerColor = MaterialTheme.colorScheme.background,
                        topBar = {
                            TopAppBar(
                                title = { Text("自律", fontWeight = FontWeight.Bold) },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = Color.Transparent
                                )
                            )
                        },
                        bottomBar = {
                            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                                tabs.forEachIndexed { index, tab ->
                                    NavigationBarItem(
                                        selected = page == index,
                                        onClick = { page = index },
                                        icon = { Icon(tab.second, contentDescription = tab.first) },
                                        label = { Text(tab.first) }
                                    )
                                }
                            }
                        },
                        floatingActionButton = {
                            if (page <= 2) {
                                FloatingActionButton(onClick = {
                                    when (page) {
                                        0 -> showTaskDialog = true
                                        1 -> showFolderDialog = true
                                        else -> todoDialogDate = calendarDate
                                    }
                                }) {
                                    Icon(Icons.Default.Add, contentDescription = "新增")
                                }
                            }
                        }
                    ) { padding ->
                        Box(Modifier.padding(padding)) {
                            when (page) {
                                0 -> TasksScreen(
                                    state = state,
                                    onStart = { controller.startTask(it) },
                                    onEdit = { editingTask = it }
                                )

                                1 -> PlansScreen(
                                    state = state,
                                    onAddPlan = { planDialogFolder = it },
                                    onAddSubPlan = { folderId, planId ->
                                        subPlanTarget = folderId to planId
                                    },
                                    onToggleSub = { f, p, s -> controller.toggleSubPlan(f, p, s) },
                                    onDeleteSub = { f, p, s -> controller.deleteSubPlan(f, p, s) },
                                    onDeletePlan = { f, p -> controller.deletePlan(f, p) },
                                    onDeleteFolder = { controller.deleteFolder(it) },
                                    onSaveReflection = { f, p, text -> controller.saveReflection(f, p, text) }
                                )

                                2 -> CalendarScreen(
                                    state = state,
                                    selected = calendarDate,
                                    onSelect = { calendarDate = it },
                                    onToggle = { controller.toggleTodo(it) },
                                    onDelete = { controller.deleteTodo(it) },
                                    onReorder = { date, order -> controller.reorderTodos(date, order) }
                                )

                                3 -> NotesScreen(
                                    state = state,
                                    onSave = { date, text -> controller.saveNote(date, text) }
                                )

                                else -> SettingsScreen(
                                    state = state,
                                    onSave = { controller.saveSettings(it) },
                                    context = context
                                )
                            }
                        }
                    }
                }

                if (showPermissionPrompt && missingPermission) {
                    AlertDialog(
                        onDismissRequest = { showPermissionPrompt = false },
                        title = { Text("打开通知") },
                        text = {
                            Text(
                                "任务到点的提醒、专注结束的提醒、专注中的倒计时，都需要通知权限才能显示在状态栏。" +
                                    "关闭后到点将收不到任何提示。"
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showPermissionPrompt = false
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }) { Text("允许") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showPermissionPrompt = false }) { Text("以后再说") }
                        }
                    )
                }

                if (showTaskDialog) {
                    TaskDialog(
                        initial = null,
                        onDismiss = { showTaskDialog = false },
                        onDelete = null,
                        onSave = { draft ->
                            controller.saveTask(null, draft)
                            showTaskDialog = false
                        }
                    )
                }

                editingTask?.let { task ->
                    TaskDialog(
                        initial = task,
                        onDismiss = { editingTask = null },
                        onDelete = {
                            controller.deleteTask(task)
                            editingTask = null
                        },
                        onSave = { draft ->
                            controller.saveTask(task, draft)
                            editingTask = null
                        }
                    )
                }

                if (showFolderDialog) {
                    FolderDialog(
                        onDismiss = { showFolderDialog = false },
                        onSave = { name, color ->
                            controller.addFolder(name, color)
                            showFolderDialog = false
                        }
                    )
                }

                planDialogFolder?.let { folderId ->
                    PlanDialog(
                        onDismiss = { planDialogFolder = null },
                        onSave = { title ->
                            controller.addPlan(folderId, title)
                            planDialogFolder = null
                        }
                    )
                }

                subPlanTarget?.let { (folderId, planId) ->
                    SubPlanDialog(
                        onDismiss = { subPlanTarget = null },
                        onSave = { title, month, day ->
                            controller.addSubPlan(folderId, planId, title, month, day)
                            subPlanTarget = null
                        }
                    )
                }

                todoDialogDate?.let { initialDate ->
                    TodoDialog(
                        initialDate = initialDate,
                        onDismiss = { todoDialogDate = null },
                        onSave = { title, date, color ->
                            controller.addTodo(title, date, color)
                            calendarDate = date
                            todoDialogDate = null
                        }
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- 任务

@Composable
private fun TaskDialog(
    initial: DailyTask?,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (TaskDraft) -> Unit
) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var minutes by remember { mutableStateOf((initial?.planMinutes ?: 25).toString()) }
    var repeat by remember { mutableStateOf(initial?.repeat ?: TaskRepeat.DAILY) }
    var weekDays by remember {
        mutableStateOf(initial?.activeWeekDays?.toSet() ?: setOf(LocalDate.now().dayOfWeek.value))
    }
    var date by remember {
        mutableStateOf(
            initial?.let { runCatching { LocalDate.parse(it.createdDate) }.getOrNull() } ?: LocalDate.now()
        )
    }
    var remind by remember { mutableStateOf(initial?.reminderTime != null) }
    var hour by remember { mutableStateOf(initial?.reminderTime?.substringBefore(':') ?: "20") }
    var minute by remember { mutableStateOf(initial?.reminderTime?.substringAfter(':') ?: "00") }
    var confirmDelete by remember { mutableStateOf(false) }

    val hourValue = hour.toIntOrNull()
    val minuteValue = minute.toIntOrNull()
    val timeOk = !remind || (hourValue != null && hourValue in 0..23 && minuteValue != null && minuteValue in 0..59)
    val minuteOk = (minutes.toIntOrNull() ?: 0) in 1..600
    val valid = title.isNotBlank() && minuteOk && timeOk &&
        (repeat != TaskRepeat.WEEKLY || weekDays.isNotEmpty())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增每日任务" else "修改任务") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("任务名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = minutes,
                    onValueChange = { minutes = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("专注时长（分钟）") },
                    suffix = { Text("分钟") },
                    singleLine = true,
                    isError = !minuteOk,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!minuteOk) {
                    Text(
                        "请填写 1 ~ 600 分钟",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Text("重复", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TaskRepeat.entries.forEach { option ->
                        FilterChip(
                            selected = repeat == option,
                            onClick = { repeat = option },
                            label = { Text(option.label) }
                        )
                    }
                }

                if (repeat == TaskRepeat.WEEKLY) {
                    Text("每周哪几天", style = MaterialTheme.typography.labelLarge)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        (1..7).forEach { d ->
                            val on = d in weekDays
                            Box(
                                Modifier
                                    .width(34.dp)
                                    .height(34.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (on) MaterialTheme.colorScheme.primary
                                        else Color.Transparent
                                    )
                                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                    .clickable {
                                        weekDays = if (on) {
                                            if (weekDays.size > 1) weekDays - d else weekDays
                                        } else weekDays + d
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    weekName(d),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (on) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                if (repeat == TaskRepeat.ONCE) {
                    Text("执行日期", style = MaterialTheme.typography.labelLarge)
                    DateWheel(date) { date = it }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = remind, onCheckedChange = { remind = it })
                    Text("设置截止提醒时间", Modifier.weight(1f))
                }

                if (remind) {
                    TimeFields(
                        hour = hour,
                        minute = minute,
                        onHour = { hour = it },
                        onMinute = { minute = it }
                    )
                    Text(
                        "到达这个时间点时，手机会弹出通知提醒你",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    if (!timeOk) {
                        Text(
                            "时间填错了：小时 0~23，分钟 0~59",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                if (onDelete != null) {
                    TextButton(onClick = {
                        if (confirmDelete) onDelete() else confirmDelete = true
                    }) {
                        Text(
                            if (confirmDelete) "再点一次确认删除" else "删除这个任务",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val reminderText = if (remind && hourValue != null && minuteValue != null) {
                        "%02d:%02d".format(hourValue, minuteValue)
                    } else null
                    onSave(
                        TaskDraft(
                            title = title,
                            minutes = minutes.toIntOrNull() ?: 25,
                            reminder = reminderText,
                            repeat = repeat,
                            weekDays = weekDays.toList(),
                            date = date
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ---------------------------------------------------------------------- 计划

@Composable
private fun FolderDialog(onDismiss: () -> Unit, onSave: (String, Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建文件夹") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("文件夹名称") },
                    placeholder = { Text("例如：学习 / 健身 / 工作") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("文件夹颜色", style = MaterialTheme.typography.labelLarge)
                ColorPicker(selected = color, palette = FolderPalette, onSelect = { color = it })
                Text(
                    "不同文件夹会用不同颜色区分，里面可以放多个计划",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name, color) }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun PlanDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建计划") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("计划名称") },
                    placeholder = { Text("例如：考研复习") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "计划本身不设时间，时间填在里面的子计划上",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name) }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun SubPlanDialog(onDismiss: () -> Unit, onSave: (String, Int?, Int?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var month by remember { mutableStateOf("") }
    var day by remember { mutableStateOf("") }

    val monthValue = month.toIntOrNull()
    val dayValue = day.toIntOrNull()
    val bothEmpty = month.isBlank() && day.isBlank()
    val dueOk = if (bothEmpty) {
        true
    } else {
        (monthValue ?: 0) in 1..12 && (dayValue ?: 0) in 1..31
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建子计划") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("子计划内容") },
                    placeholder = { Text("例如：背完 3000 个单词") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("预完成时间（可以不填）", style = MaterialTheme.typography.labelLarge)
                MonthDayFields(
                    month = month,
                    day = day,
                    onMonth = { month = it },
                    onDay = { day = it }
                )
                if (!dueOk) {
                    Text(
                        "月和日要一起填：月 1~12，日 1~31",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && dueOk,
                onClick = {
                    onSave(
                        name,
                        if (bothEmpty) null else monthValue,
                        if (bothEmpty) null else dayValue
                    )
                }
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ---------------------------------------------------------------------- 待办

@Composable
private fun TodoDialog(
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onSave: (String, LocalDate, Int) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(initialDate) }
    var color by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增待办") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("待办内容") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("日期", style = MaterialTheme.typography.labelLarge)
                DateWheel(date) { date = it }
                Text("颜色标签", style = MaterialTheme.typography.labelLarge)
                ColorPicker(selected = color, palette = TodoPalette, onSelect = { color = it })
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = { onSave(title, date, color) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
