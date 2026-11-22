@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.zilu.app

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.NumberPicker
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifier.ensureChannels(this)
        setContent { ZiluApp() }
    }
}

private data class TaskDraft(
    val title: String,
    val minutes: Int,
    val reminder: String?,
    val repeat: TaskRepeat,
    val weekDays: List<Int>,
    val date: LocalDate
)

private class ZiluController(private val context: Context) {
    private val repo = ZiluRepository(context)

    var state by mutableStateOf(repo.load())
        private set

    fun refresh() {
        val latest = repo.load()
        if (latest != state) state = latest
    }

    /** Always edits the freshest saved copy, so changes made by the timer service are never overwritten. */
    private fun update(change: (AppState) -> AppState) {
        val next = change(repo.load())
        repo.save(next)
        state = next
    }

    fun saveTask(existing: DailyTask?, draft: TaskDraft) {
        val minutes = draft.minutes.coerceIn(1, 240)
        val weekDays = if (draft.repeat == TaskRepeat.WEEKLY) draft.weekDays.sorted() else emptyList()
        val created = when {
            draft.repeat == TaskRepeat.ONCE -> draft.date.toString()
            existing != null -> existing.createdDate
            else -> LocalDate.now().toString()
        }
        val base = existing ?: DailyTask(title = draft.title.trim(), minutes = minutes)
        val task = base.copy(
            title = draft.title.trim(), minutes = minutes, reminderTime = draft.reminder,
            repeat = draft.repeat, weekDays = weekDays, createdDate = created
        )
        update { s ->
            s.copy(tasks = if (existing == null) s.tasks + task else s.tasks.map { if (it.id == task.id) task else it })
        }
        ReminderScheduler.schedule(context, task)
    }

    fun deleteTask(task: DailyTask) {
        update { s -> s.copy(tasks = s.tasks.filterNot { it.id == task.id }) }
        ReminderScheduler.cancel(context, task.id)
    }

    fun startTask(task: DailyTask) {
        val session = TaskTimerService.start(context, task)
        update { it.copy(activeFocus = session) }
    }

    /** Quit a focus session early: stop the timer and notification, keep the task red, and store the new duration. */
    fun exitFocus(minutes: Int) {
        TaskTimerService.stop(context)
        update { s ->
            val focus = s.activeFocus
            val tasks = if (focus == null) s.tasks
            else s.tasks.map { if (it.id == focus.taskId) it.copy(minutes = minutes.coerceIn(1, 240)) else it }
            s.copy(activeFocus = null, tasks = tasks)
        }
    }

    /** Safety net: if nothing else finished a session whose time has passed, finish it now. */
    fun checkFocusDue() {
        val focus = state.activeFocus ?: return
        if (System.currentTimeMillis() >= focus.endAtMillis + 1500L) {
            FocusFinisher.finish(context, focus.taskId, focus.title)
            refresh()
        }
    }

    fun addPlan(title: String, date: String, goalNames: List<String>) {
        val goals = goalNames.filter { it.isNotBlank() }.map { SubGoal(title = it.trim()) }.ifEmpty { listOf(SubGoal(title = "子目标 1")) }
        update { it.copy(plans = it.plans + Plan(title = title.trim(), dueDate = date, subGoals = goals)) }
    }

    fun cycleGoal(plan: Plan, goal: SubGoal) {
        val next = when (goal.status) {
            ItemStatus.TODO -> ItemStatus.IN_PROGRESS
            ItemStatus.IN_PROGRESS -> ItemStatus.DONE
            ItemStatus.DONE -> ItemStatus.TODO
        }
        update { s ->
            s.copy(plans = s.plans.map { p ->
                if (p.id == plan.id) p.copy(subGoals = p.subGoals.map { if (it.id == goal.id) it.copy(status = next) else it }) else p
            })
        }
    }

    fun addGoal(plan: Plan) {
        update { s ->
            s.copy(plans = s.plans.map { p ->
                if (p.id == plan.id) p.copy(subGoals = p.subGoals + SubGoal(title = "子目标 ${p.subGoals.size + 1}")) else p
            })
        }
    }

    fun saveReflection(plan: Plan, value: String) {
        update { s -> s.copy(plans = s.plans.map { if (it.id == plan.id) it.copy(completionReflection = value) else it }) }
    }

    fun saveNote(date: LocalDate, content: String) {
        update { s -> s.copy(notes = s.notes.filterNot { it.date == date.toString() } + DailyNote(date.toString(), content)) }
    }

    fun saveSettings(settings: AppSettings) = update { it.copy(settings = settings) }

    fun addTodo(title: String, date: LocalDate) {
        update { it.copy(todos = it.todos + TodoItem(title = title.trim(), date = date.toString())) }
    }

    fun toggleTodo(id: String) {
        update { s -> s.copy(todos = s.todos.map { if (it.id == id) it.copy(done = !it.done) else it }) }
    }

    fun deleteTodo(id: String) {
        update { s -> s.copy(todos = s.todos.filterNot { it.id == id }) }
    }
}

@Composable
private fun ZiluApp() {
    val context = LocalContext.current
    val controller = remember { ZiluController(context.applicationContext) }
    val state = controller.state
    val density = LocalDensity.current
    var page by rememberSaveable { mutableIntStateOf(0) }
    var showAddTask by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<DailyTask?>(null) }
    var showAddPlan by remember { mutableStateOf(false) }
    var showAddTodo by remember { mutableStateOf(false) }
    var calendarDate by remember { mutableStateOf(LocalDate.now()) }
    var askNotification by rememberSaveable { mutableStateOf(true) }
    val needNotificationPermission = Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { askNotification = false }

    LaunchedEffect(Unit) { ReminderScheduler.rescheduleAll(context) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            controller.refresh()
            controller.checkFocusDue()
        }
    }

    val primary = when (state.settings.themeName) {
        "松石绿" -> Color(0xFF006B5B)
        "暮光紫" -> Color(0xFF6950A1)
        else -> Color(0xFF875E12)
    }
    val colors = if (state.settings.darkMode) darkColorScheme(primary = primary) else lightColorScheme(primary = primary, tertiary = Color(0xFF23865A))
    val focus = state.activeFocus
    val tabs = listOf("任务" to Icons.Default.Today, "计划" to Icons.Default.Flag, "日历" to Icons.Default.CalendarMonth, "小记" to Icons.Default.EditNote, "设置" to Icons.Default.Settings)

    CompositionLocalProvider(LocalDensity provides Density(density.density, state.settings.fontScale)) {
        MaterialTheme(colorScheme = colors) {
            if (focus != null) {
                FocusOverlay(focus, onExit = controller::exitFocus)
            } else {
                Scaffold(
                    topBar = { TopAppBar(title = { Text("自律", fontWeight = FontWeight.Bold) }) },
                    bottomBar = {
                        NavigationBar {
                            tabs.forEachIndexed { index, tab ->
                                NavigationBarItem(
                                    selected = page == index, onClick = { page = index },
                                    icon = { Icon(tab.second, tab.first) }, label = { Text(tab.first) }
                                )
                            }
                        }
                    },
                    floatingActionButton = {
                        if (page <= 2) {
                            FloatingActionButton(onClick = {
                                when (page) {
                                    0 -> showAddTask = true
                                    1 -> showAddPlan = true
                                    else -> showAddTodo = true
                                }
                            }) { Icon(Icons.Default.Add, "新增") }
                        }
                    }
                ) { padding ->
                    Box(Modifier.padding(padding)) {
                        when (page) {
                            0 -> TasksScreen(state, onStart = controller::startTask, onEdit = { editingTask = it })
                            1 -> PlansScreen(state, controller::cycleGoal, controller::addGoal, controller::saveReflection)
                            2 -> CalendarScreen(state, calendarDate, { calendarDate = it }, controller::toggleTodo, controller::deleteTodo)
                            3 -> NotesScreen(state, controller::saveNote)
                            else -> SettingsScreen(state, controller::saveSettings, context)
                        }
                    }
                }
            }
            if (askNotification && needNotificationPermission) {
                NotificationPrompt(
                    onAllow = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    onDismiss = { askNotification = false }
                )
            }
            if (showAddTask) {
                TaskDialog(null, { showAddTask = false }, null) { draft -> controller.saveTask(null, draft); showAddTask = false }
            }
            editingTask?.let { task ->
                TaskDialog(task, { editingTask = null }, { controller.deleteTask(task); editingTask = null }) { draft ->
                    controller.saveTask(task, draft); editingTask = null
                }
            }
            if (showAddPlan) {
                PlanDialog({ showAddPlan = false }) { t, d, goals -> controller.addPlan(t, d, goals); showAddPlan = false }
            }
            if (showAddTodo) {
                TodoDialog(calendarDate, { showAddTodo = false }) { title, date ->
                    controller.addTodo(title, date); calendarDate = date; showAddTodo = false
                }
            }
        }
    }
}

@Composable
private fun NotificationPrompt(onAllow: () -> Unit, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("打开通知？") },
    text = { Text("任务提醒、专注倒计时和时间到的提醒，都需要通知权限才能显示在手机状态栏。") },
    confirmButton = { TextButton(onClick = onAllow) { Text("打开") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("暂不") } }
)

private fun isComplete(task: DailyTask, day: LocalDate, state: AppState) =
    state.completionLogs.any { it.taskId == task.id && it.date == day.toString() }

private fun redGreen(done: Boolean) = if (done) Color(0xFF23865A) else Color(0xFFBE4B4B)

private fun weekName(day: Int) = listOf("一", "二", "三", "四", "五", "六", "日")[(day - 1).coerceIn(0, 6)]

private fun repeatLabel(task: DailyTask): String = when (task.repeat) {
    TaskRepeat.DAILY -> "每天"
    TaskRepeat.WEEKLY -> "每周" + task.activeWeekDays.sorted().joinToString("") { weekName(it) }
    TaskRepeat.ONCE -> "仅 ${task.createdDate}"
}

/** Consecutive completed occurrences, skipping days the task does not apply to. */
private fun streak(task: DailyTask, state: AppState): Int {
    val today = LocalDate.now()
    var count = 0
    for (i in 0..400) {
        val day = today.minusDays(i.toLong())
        if (!task.occursOn(day)) continue
        if (isComplete(task, day, state)) count++ else if (day != today) break
    }
    return count
}

@Composable
private fun TasksScreen(state: AppState, onStart: (DailyTask) -> Unit, onEdit: (DailyTask) -> Unit) {
    val today = LocalDate.now()
    val todayTasks = state.tasks.filter { it.occursOn(today) }
    val otherTasks = state.tasks.filterNot { it.occursOn(today) }
    val done = todayTasks.count { isComplete(it, today, state) }
    val progress = if (todayTasks.isEmpty()) 0 else (done * 100f / todayTasks.size).roundToInt()
    val quote = quoteOfDay(today)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(quote.en, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
                    Text(quote.zh, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text("— ${quote.author}", Modifier.fillMaxWidth(), textAlign = TextAlign.End, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(today.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)), style = MaterialTheme.typography.titleMedium)
                Text("今日完成度 $progress%", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                LinearProgressIndicator({ progress / 100f }, Modifier.fillMaxWidth().height(8.dp).clip(MaterialTheme.shapes.small))
            }
        }
        if (todayTasks.isEmpty()) item { Text("今天没有任务，点右下角 + 新增", style = MaterialTheme.typography.bodyMedium) }
        items(todayTasks, key = { it.id }) { task ->
            TaskCard(task, isComplete(task, today, state), streak(task, state), true, onStart, onEdit)
        }
        if (otherTasks.isNotEmpty()) {
            item { Text("今天不执行的任务", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.outline) }
            items(otherTasks, key = { it.id }) { task ->
                TaskCard(task, false, 0, false, onStart, onEdit)
            }
        }
    }
}

@Composable
private fun TaskCard(task: DailyTask, done: Boolean, days: Int, activeToday: Boolean, onStart: (DailyTask) -> Unit, onEdit: (DailyTask) -> Unit) {
    val tint = if (activeToday) redGreen(done) else MaterialTheme.colorScheme.outline
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = tint.copy(alpha = .12f))) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null, tint = tint)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(task.title, fontWeight = FontWeight.Bold)
                Text(
                    "${task.minutes} 分钟 · ${repeatLabel(task)}" + (task.reminderTime?.let { " · $it 提醒" } ?: ""),
                    style = MaterialTheme.typography.bodySmall
                )
                if (days > 0) Text("已坚持 $days 天", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = { onEdit(task) }) { Icon(Icons.Default.Edit, "修改") }
            if (activeToday) {
                if (done) Text("已完成", color = redGreen(true), fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
                else Button(onClick = { onStart(task) }) { Icon(Icons.Default.PlayArrow, null); Text("开始") }
            }
        }
    }
}

@Composable
private fun PlansScreen(state: AppState, cycle: (Plan, SubGoal) -> Unit, addGoal: (Plan) -> Unit, saveReflection: (Plan, String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("长期计划", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        items(state.plans.size) { i -> PlanCard(state.plans[i], cycle, addGoal, saveReflection) }
    }
}

@Composable
private fun PlanCard(plan: Plan, cycle: (Plan, SubGoal) -> Unit, addGoal: (Plan) -> Unit, saveReflection: (Plan, String) -> Unit) {
    var open by remember { mutableStateOf(false) }; var note by remember(plan.id) { mutableStateOf(plan.completionReflection) }
    val color = when (plan.calculatedStatus) { ItemStatus.DONE -> redGreen(true); ItemStatus.IN_PROGRESS -> Color(0xFFC48712); else -> redGreen(false) }
    ElevatedCard { Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(plan.title, fontWeight = FontWeight.Bold); Text("截止：${plan.dueDate}" + if (plan.isOverdue) " · 已逾期" else "", color = if (plan.isOverdue) MaterialTheme.colorScheme.error else LocalContentColor.current, style = MaterialTheme.typography.bodySmall) }; Surface(color = color.copy(alpha = .15f), shape = MaterialTheme.shapes.small) { Text(plan.calculatedStatus.label, Modifier.padding(8.dp), color = color) } }
        TextButton(onClick = { open = !open }) { Text(if (open) "收起" else "查看子目标") }
        if (open) { plan.subGoals.forEach { goal -> Row(Modifier.fillMaxWidth().clickable { cycle(plan, goal) }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (goal.status == ItemStatus.DONE) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null, tint = when (goal.status) { ItemStatus.DONE -> redGreen(true); ItemStatus.IN_PROGRESS -> Color(0xFFC48712); else -> redGreen(false) }); Spacer(Modifier.width(8.dp)); Text(goal.title, Modifier.weight(1f)); Text(goal.status.label, style = MaterialTheme.typography.labelSmall) } }; TextButton(onClick = { addGoal(plan) }) { Icon(Icons.Default.Add, null); Text("添加子目标") }; OutlinedTextField(note, { note = it }, label = { Text("完成心得") }, modifier = Modifier.fillMaxWidth()); TextButton(onClick = { saveReflection(plan, note) }) { Text("保存") } }
    } }
}

@Composable
private fun CalendarScreen(
    state: AppState,
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit,
    onToggle: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    var month by remember { mutableStateOf(YearMonth.from(selected)) }
    LaunchedEffect(selected) { month = YearMonth.from(selected) }
    val firstPadding = month.atDay(1).dayOfWeek.value % 7
    val cells: List<LocalDate?> = List(firstPadding) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    val todosOfSelected = state.todos.filter { it.date == selected.toString() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.Default.ChevronLeft, "上个月") }
                Text(
                    month.format(DateTimeFormatter.ofPattern("yyyy 年 M 月", Locale.CHINA)), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
                )
                IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.Default.ChevronRight, "下个月") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth()) {
                listOf("日", "一", "二", "三", "四", "五", "六").forEach {
                    Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                cells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        week.forEach { day ->
                            if (day == null) {
                                Box(Modifier.weight(1f).aspectRatio(1f))
                            } else {
                                // Day colour comes only from that day's to-dos: any unfinished = red, all finished = green.
                                val todos = state.todos.filter { it.date == day.toString() }
                                val hasTodos = todos.isNotEmpty()
                                val allDone = hasTodos && todos.all { it.done }
                                val tint = redGreen(allDone)
                                val isSelected = day == selected
                                Box(
                                    Modifier.weight(1f).aspectRatio(1f).clip(MaterialTheme.shapes.small)
                                        .background(if (hasTodos) tint.copy(alpha = if (isSelected) .45f else .22f) else Color.Transparent)
                                        .then(if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small) else Modifier)
                                        .clickable { onSelect(day) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(day.dayOfMonth.toString(), color = if (hasTodos || isSelected) LocalContentColor.current else MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                        repeat(7 - week.size) { Box(Modifier.weight(1f).aspectRatio(1f)) }
                    }
                }
            }
        }
        item {
            Text(
                selected.format(DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)) + " 待办事项",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold
            )
        }
        if (todosOfSelected.isEmpty()) item { Text("这一天没有待办，点右下角 + 添加", style = MaterialTheme.typography.bodyMedium) }
        items(todosOfSelected, key = { it.id }) { todo ->
            Surface(color = redGreen(todo.done).copy(alpha = .12f), shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(26.dp).clip(RoundedCornerShape(4.dp)).background(redGreen(todo.done)).clickable { onToggle(todo.id) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (todo.done) Icon(Icons.Default.Check, "已完成", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(todo.title, Modifier.weight(1f))
                    IconButton(onClick = { onDelete(todo.id) }) { Icon(Icons.Default.Delete, "删除") }
                }
            }
        }
    }
}

@Composable
private fun NotesScreen(state: AppState, save: (LocalDate, String) -> Unit) {
    var selected by remember { mutableStateOf(LocalDate.now()) }
    fun savedText(day: LocalDate) = state.notes.firstOrNull { it.date == day.toString() }?.content.orEmpty()
    // Re-read the stored diary whenever the chosen day changes, so each day shows its own entry.
    var text by remember(selected) { mutableStateOf(savedText(selected)) }
    LaunchedEffect(selected, text) {
        if (text != savedText(selected)) {
            delay(700)
            save(selected, text)
        }
    }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("每日小记", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        DateWheel(selected) { picked ->
            if (picked != selected) {
                if (text != savedText(selected)) save(selected, text) // keep what was typed before switching
                selected = picked
            }
        }
        OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().weight(1f), label = { Text("${selected} 的日记（自动保存）") })
        Button(onClick = { save(selected, text) }, Modifier.fillMaxWidth()) { Text("保存") }
    }
}

private fun canExactAlarm(context: Context) = Alarms.canExact(context)

private fun openNotificationSettings(context: Context) {
    val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
    runCatching { context.startActivity(intent) }
}

private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= 31) {
        val intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(intent) }
    }
}

@Composable
private fun SettingsScreen(state: AppState, save: (AppSettings) -> Unit, context: Context) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("深色模式", Modifier.weight(1f))
            Switch(state.settings.darkMode, { save(state.settings.copy(darkMode = it)) })
        }
        Text("字体大小 ${(state.settings.fontScale * 100).roundToInt()}%")
        Slider(value = state.settings.fontScale, onValueChange = { save(state.settings.copy(fontScale = it)) }, valueRange = .85f..1.3f)
        Text("主题颜色")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("琥珀金", "松石绿", "暮光紫").forEach { name ->
                FilterChip(selected = state.settings.themeName == name, onClick = { save(state.settings.copy(themeName = name)) }, label = { Text(name) })
            }
        }
        Text("通知与提醒")
        OutlinedButton(onClick = { openNotificationSettings(context) }, Modifier.fillMaxWidth()) { Text("打开通知设置") }
        if (!canExactAlarm(context)) {
            OutlinedButton(onClick = { openExactAlarmSettings(context) }, Modifier.fillMaxWidth()) { Text("允许精确闹钟（让提醒准时）") }
        }
        Text(
            "若手机仍收不到提醒：请在系统设置里允许「自律」自启动、后台运行，并把电池策略设为「不限制」。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { shareInstalledApp(context) }, Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text("分享给好友（微信 / QQ 等）")
        }
    }
}

private fun shareInstalledApp(context: Context) {
    runCatching {
        val source = File(context.applicationInfo.sourceDir)
        val copy = File(context.cacheDir, "zilu.apk")
        source.copyTo(copy, overwrite = true)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", copy)
        val send = Intent(Intent.ACTION_SEND).setType("application/vnd.android.package-archive")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "分享自律"))
    }
}

@Composable
private fun FocusOverlay(session: FocusSession, onExit: (Int) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var askExit by remember { mutableStateOf(false) }
    var minutes by remember(session.taskId) { mutableStateOf(session.minutes.toString()) }
    LaunchedEffect(session.endAtMillis) {
        while (now < session.endAtMillis) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    BackHandler { askExit = true }
    val remain = (session.endAtMillis - now).coerceAtLeast(0L)
    val clock = "%02d:%02d".format(remain / 60000, (remain / 1000) % 60)
    val total = (session.endAtMillis - session.startedAtMillis).coerceAtLeast(1L)
    Surface(Modifier.fillMaxSize().systemBarsPadding(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("专注中", style = MaterialTheme.typography.headlineSmall)
            Text(session.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Text(clock, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 32.dp))
            LinearProgressIndicator({ 1f - remain.toFloat() / total }, Modifier.fillMaxWidth().height(8.dp).clip(MaterialTheme.shapes.small))
            Text("倒计时已显示在状态栏，切到后台也会继续", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
            Spacer(Modifier.height(32.dp))
            OutlinedButton(onClick = { askExit = true }) { Icon(Icons.Default.Close, null); Spacer(Modifier.width(6.dp)); Text("退出专注") }
        }
    }
    if (askExit) {
        AlertDialog(
            onDismissRequest = { askExit = false },
            title = { Text("退出专注？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("退出后本次计时作废，任务保持未完成（红色）。可以顺便修改这个任务的专注时长：")
                    OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(3) }, label = { Text("专注时长（分钟）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { onExit(minutes.toIntOrNull() ?: session.minutes) }, enabled = minutes.isNotBlank()) { Text("退出") } },
            dismissButton = { TextButton(onClick = { askExit = false }) { Text("继续专注") } }
        )
    }
}

@Composable
private fun WheelNumber(value: Int, range: IntRange, onValue: (Int) -> Unit, modifier: Modifier = Modifier) {
    AndroidView(factory = { NumberPicker(it).apply { minValue = range.first; maxValue = range.last; wrapSelectorWheel = true; this.value = value; setOnValueChangedListener { _, _, new -> onValue(new) } } }, update = { if (it.value != value) it.value = value }, modifier = modifier.height(110.dp))
}

@Composable
private fun TimeWheel(initial: String?, onValue: (String) -> Unit) {
    var hour by remember(initial) { mutableIntStateOf(initial?.substringBefore(':')?.toIntOrNull() ?: 20) }; var minute by remember(initial) { mutableIntStateOf(initial?.substringAfter(':')?.toIntOrNull() ?: 0) }
    LaunchedEffect(hour, minute) { onValue("%02d:%02d".format(hour, minute)) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) { WheelNumber(hour, 0..23, { hour = it }, Modifier.weight(1f)); Text("时"); WheelNumber(minute, 0..59, { minute = it }, Modifier.weight(1f)); Text("分") }
}

@Composable
private fun DateWheel(value: LocalDate, onValue: (LocalDate) -> Unit) {
    var year by remember(value) { mutableIntStateOf(value.year) }
    var month by remember(value) { mutableIntStateOf(value.monthValue) }
    var day by remember(value) { mutableIntStateOf(value.dayOfMonth) }
    LaunchedEffect(year, month, day) {
        val safeDay = day.coerceAtMost(YearMonth.of(year, month).lengthOfMonth())
        onValue(LocalDate.of(year, month, safeDay))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        WheelNumber(year, LocalDate.now().year - 5..LocalDate.now().year + 10, { year = it }, Modifier.weight(1f)); Text("年")
        WheelNumber(month, 1..12, { month = it }, Modifier.weight(1f)); Text("月")
        WheelNumber(day, 1..31, { day = it }, Modifier.weight(1f)); Text("日")
    }
}

@Composable
private fun TaskDialog(initial: DailyTask?, dismiss: () -> Unit, delete: (() -> Unit)?, save: (TaskDraft) -> Unit) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var minutes by remember { mutableStateOf((initial?.minutes ?: 25).toString()) }
    var repeat by remember { mutableStateOf(initial?.repeat ?: TaskRepeat.DAILY) }
    var weekDays by remember { mutableStateOf(initial?.activeWeekDays?.toSet() ?: setOf(LocalDate.now().dayOfWeek.value)) }
    var date by remember {
        mutableStateOf(
            if (initial != null && initial.repeat == TaskRepeat.ONCE) runCatching { LocalDate.parse(initial.createdDate) }.getOrNull() ?: LocalDate.now() else LocalDate.now()
        )
    }
    var remind by remember { mutableStateOf(initial?.reminderTime != null) }
    var reminder by remember { mutableStateOf(initial?.reminderTime ?: "20:00") }
    var confirmDelete by remember { mutableStateOf(false) }
    val valid = title.isNotBlank() && (repeat != TaskRepeat.WEEKLY || weekDays.isNotEmpty())
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (initial == null) "新增每日任务" else "修改任务") },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OutlinedTextField(title, { title = it }, label = { Text("任务") }, modifier = Modifier.fillMaxWidth()) }
                item {
                    OutlinedTextField(
                        minutes, { minutes = it.filter(Char::isDigit).take(3) },
                        label = { Text("时长（分钟）") }, modifier = Modifier.fillMaxWidth()
                    )
                }
                item { Text("重复") }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TaskRepeat.entries.forEach { option ->
                            FilterChip(selected = repeat == option, onClick = { repeat = option }, label = { Text(option.label) })
                        }
                    }
                }
                if (repeat == TaskRepeat.WEEKLY) {
                    item { Text("每周哪几天") }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            (1..7).forEach { d ->
                                val on = d in weekDays
                                Box(
                                    Modifier.size(30.dp).clip(CircleShape)
                                        .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                        .clickable { weekDays = if (on) (if (weekDays.size > 1) weekDays - d else weekDays) else weekDays + d },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(weekName(d), style = MaterialTheme.typography.labelMedium, color = if (on) MaterialTheme.colorScheme.onPrimary else LocalContentColor.current)
                                }
                            }
                        }
                    }
                }
                if (repeat == TaskRepeat.ONCE) {
                    item { Text("执行日期") }
                    item { DateWheel(date) { date = it } }
                }
                item { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(remind, { remind = it }); Text("设置提醒") } }
                if (remind) item { TimeWheel(reminder) { reminder = it } }
                if (delete != null) {
                    item {
                        TextButton(onClick = { if (confirmDelete) delete() else confirmDelete = true }) {
                            Text(if (confirmDelete) "再点一次确认删除" else "删除任务", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { save(TaskDraft(title, minutes.toIntOrNull() ?: 25, reminder.takeIf { remind }, repeat, weekDays.toList(), date)) },
                enabled = valid
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } }
    )
}

@Composable
private fun TodoDialog(initialDate: LocalDate, dismiss: () -> Unit, save: (String, LocalDate) -> Unit) {
    var title by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(initialDate) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("新增待办任务") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OutlinedTextField(title, { title = it }, label = { Text("待办内容") }, modifier = Modifier.fillMaxWidth()) }
                item { Text("日期") }
                item { DateWheel(date) { date = it } }
            }
        },
        confirmButton = { TextButton(onClick = { save(title, date) }, enabled = title.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } }
    )
}

@Composable
private fun PlanDialog(dismiss: () -> Unit, save: (String, String, List<String>) -> Unit) {
    var title by remember { mutableStateOf("") }; var date by remember { mutableStateOf(LocalDate.now().plusMonths(1)) }; var goals by remember { mutableStateOf(listOf("子目标 1")) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("新增长期计划") }, text = { LazyColumn(Modifier.heightIn(max = 470.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { item { OutlinedTextField(title, { title = it }, label = { Text("计划") }, modifier = Modifier.fillMaxWidth()) }; item { Text("截止时间") }; item { DateWheel(date) { date = it } }; items(goals.size) { i -> OutlinedTextField(goals[i], { value -> goals = goals.toMutableList().also { it[i] = value } }, label = { Text("子目标 ${i + 1}") }, modifier = Modifier.fillMaxWidth()) }; item { TextButton(onClick = { goals = goals + "" }) { Icon(Icons.Default.Add, null); Text("添加子目标") } } } }, confirmButton = { TextButton(onClick = { save(title, date.toString(), goals) }, enabled = title.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
