@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.zilu.app

import android.Manifest
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ZiluApp() }
    }
}

private class ZiluController(private val context: Context) {
    private val repository = ZiluRepository(context)
    var state by mutableStateOf(repository.load()); private set
    private fun update(next: AppState) { state = next; repository.save(next) }
    private fun timestamp() = LocalDateTime.now().toString()
    fun addTask(title: String, repeat: RepeatRule, reminder: String?) {
        val task = DailyTask(title = title.trim(), repeat = repeat, reminderTime = reminder?.trim()?.ifBlank { null })
        update(state.copy(tasks = state.tasks + task)); ReminderScheduler.schedule(context, task.id, task.title, task.reminderTime)
    }
    fun changeTask(task: DailyTask, status: ItemStatus): Boolean {
        if (status == task.status) return false
        // Repeating tasks keep their schedule definition; the dated action log is the source of truth for each occurrence.
        update(state.copy(tasks = state.tasks.map { if (it.id == task.id) it.copy(statusChangedAt = timestamp()) else it }, statusLogs = state.statusLogs + StatusLog(itemType = "每日任务", itemId = task.id, title = task.title, oldStatus = task.status, newStatus = status)))
        return task.status != ItemStatus.DONE && status == ItemStatus.DONE
    }
    fun addPlan(title: String, dueText: String, dueDate: String?, goals: List<String>, reminder: String?) {
        val plan = Plan(title = title.trim(), dueText = dueText.trim().ifBlank { "完成时间待定" }, dueDate = dueDate?.trim()?.ifBlank { null }, subGoals = goals.filter { it.isNotBlank() }.map { SubGoal(title = it.trim()) }, reminderTime = reminder?.trim()?.ifBlank { null })
        update(state.copy(plans = state.plans + plan)); ReminderScheduler.schedule(context, plan.id, plan.title, plan.reminderTime)
    }
    fun changePlan(plan: Plan, status: ItemStatus): Boolean {
        if (plan.status == status) return false
        val updated = plan.copy(status = status, statusChangedAt = timestamp())
        update(state.copy(plans = state.plans.map { if (it.id == plan.id) updated else it }, statusLogs = state.statusLogs + StatusLog(itemType = "计划", itemId = plan.id, title = plan.title, oldStatus = plan.status, newStatus = status)))
        return plan.status != ItemStatus.DONE && status == ItemStatus.DONE
    }
    fun changeGoal(plan: Plan, goal: SubGoal, status: ItemStatus): Boolean {
        if (goal.status == status) return false
        val updatedPlan = plan.copy(subGoals = plan.subGoals.map { if (it.id == goal.id) goal.copy(status = status, changedAt = timestamp()) else it }, statusChangedAt = timestamp())
        val changed = updatedPlan.calculatedStatus
        update(state.copy(plans = state.plans.map { if (it.id == plan.id) updatedPlan else it }, statusLogs = state.statusLogs + StatusLog(itemType = "子目标", itemId = goal.id, title = "${plan.title} · ${goal.title}", oldStatus = goal.status, newStatus = status)))
        return plan.calculatedStatus != ItemStatus.DONE && changed == ItemStatus.DONE
    }
    fun saveReflection(plan: Plan, text: String) { update(state.copy(plans = state.plans.map { if (it.id == plan.id) it.copy(completionReflection = text) else it })) }
    fun deletePlan(plan: Plan) { update(state.copy(plans = state.plans.filterNot { it.id == plan.id }, deletedPlans = state.deletedPlans + plan.copy(deletedAt = timestamp()))) }
    fun restorePlan(plan: Plan) { update(state.copy(deletedPlans = state.deletedPlans.filterNot { it.id == plan.id }, plans = state.plans + plan.copy(deletedAt = null))) }
    fun saveSummary(summary: DailySummary) { update(state.copy(summaries = state.summaries.filterNot { it.date == summary.date } + summary.copy(updatedAt = timestamp()))) }
    fun saveFocus(record: FocusRecord) { update(state.copy(focusRecords = state.focusRecords + record)) }
    fun saveSettings(settings: AppSettings) { update(state.copy(settings = settings)) }
    fun export() = repository.export(state)
    fun restore(json: String): Result<Unit> = runCatching { state = repository.restore(json) }
}

@Composable
private fun ZiluApp() {
    val context = LocalContext.current
    val controller = remember { ZiluController(context.applicationContext) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var addTask by remember { mutableStateOf(false) }
    var addPlan by remember { mutableStateOf(false) }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
    val state = controller.state
    val primary = when (state.settings.themeName) {
        "松石绿" -> Color(0xFF006B5B)
        "暮光紫" -> Color(0xFF6950A1)
        else -> Color(0xFF875E12)
    }
    val colors = if (state.settings.darkMode) darkColorScheme(primary = primary, secondary = Color(0xFF8ED1C1)) else lightColorScheme(primary = primary, secondary = Color(0xFF006B5B), tertiary = Color(0xFFC34545))
    MaterialTheme(colorScheme = colors, typography = Typography(bodyLarge = LocalTextStyle.current.copy(fontSize = (16 * state.settings.fontScale).sp))) {
        Scaffold(
            topBar = { TopAppBar(title = { Column { Text("自律", fontWeight = FontWeight.Bold); Text("Discipline, day by day", style = MaterialTheme.typography.labelSmall) } }) },
            bottomBar = { NavigationBar { listOf("今日" to Icons.Default.Today, "计划" to Icons.Default.Flag, "日历" to Icons.Default.CalendarMonth, "专注" to Icons.Default.Timer, "设置" to Icons.Default.Settings).forEachIndexed { i, (label, icon) -> NavigationBarItem(selected = page == i, onClick = { page = i }, icon = { Icon(icon, label) }, label = { Text(label) }) } } },
            floatingActionButton = { if (page == 0 || page == 1) FloatingActionButton(onClick = { if (page == 0) addTask = true else addPlan = true }) { Icon(Icons.Default.Add, "新建") } }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (page) {
                    0 -> TodayScreen(state) { task, status -> controller.changeTask(task, status).also { if (it) playCompletionSound(context, state.settings) } }
                    1 -> PlansScreen(state, { plan, status -> controller.changePlan(plan, status).also { if (it) playCompletionSound(context, state.settings) } }, { plan, goal, status -> controller.changeGoal(plan, goal, status).also { if (it) playCompletionSound(context, state.settings) } }, controller::saveReflection, controller::deletePlan)
                    2 -> CalendarScreen(state)
                    3 -> FocusScreen(controller::saveFocus, { playCompletionSound(context, state.settings) })
                    else -> SettingsScreen(state, controller, context)
                }
            }
        }
    }
    if (addTask) TaskDialog(onDismiss = { addTask = false }, onSave = { title, repeat, reminder -> controller.addTask(title, repeat, reminder); addTask = false })
    if (addPlan) PlanDialog(onDismiss = { addPlan = false }, onSave = { title, dueText, dueDate, goals, reminder -> controller.addPlan(title, dueText, dueDate, goals, reminder); addPlan = false })
}

private fun playCompletionSound(context: Context, settings: AppSettings) {
    runCatching { RingtoneManager.getRingtone(context, settings.soundUri?.let(Uri::parse) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)).play() }
}

private val quotes = listOf(
    "坚持不是因为有希望，而是坚持才有希望。" to "Persistence creates hope.",
    "成功就是日复一日的小努力。" to "Success is the sum of small efforts, repeated day in and day out.",
    "不必很厉害才开始，要开始才会很厉害。" to "You do not have to be great to start, but you have to start to be great.",
    "The secret of getting ahead is getting started." to "开始，是领先的秘诀。"
)

private fun statusColor(status: ItemStatus) = when (status) {
    ItemStatus.DONE -> Color(0xFF23865A)
    ItemStatus.IN_PROGRESS -> Color(0xFFC48712)
    ItemStatus.TODO -> Color(0xFFBE4B4B)
}

private fun taskStatusOn(task: DailyTask, date: LocalDate, state: AppState): ItemStatus =
    state.statusLogs.asReversed().firstOrNull { it.itemType == "每日任务" && it.itemId == task.id && it.at.startsWith(date.toString()) }?.newStatus ?: ItemStatus.TODO

@Composable
private fun StatusBadge(status: ItemStatus, modifier: Modifier = Modifier) {
    Surface(color = statusColor(status).copy(alpha = .14f), contentColor = statusColor(status), shape = MaterialTheme.shapes.small, modifier = modifier) {
        Text(status.label, Modifier.padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusMenu(status: ItemStatus, onSelect: (ItemStatus) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        StatusBadge(status, Modifier.clickable { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ItemStatus.entries.forEach { item -> DropdownMenuItem(text = { Text(item.label, color = statusColor(item)) }, onClick = { onSelect(item); expanded = false }) }
        }
    }
}

@Composable
private fun TodayScreen(state: AppState, onChange: (DailyTask, ItemStatus) -> Unit) {
    val today = LocalDate.now()
    val todayTasks = state.tasks.filter { task ->
        task.date == today.toString() || task.repeat == RepeatRule.DAILY || (task.repeat == RepeatRule.WEEKLY && runCatching { LocalDate.parse(task.date).dayOfWeek == today.dayOfWeek }.getOrDefault(false))
    }.map { it.copy(status = taskStatusOn(it, today, state)) }
    val points = todayTasks.sumOf { it.status.weight.toDouble() }
    val percent = if (todayTasks.isEmpty()) 0 else (points / todayTasks.size * 100).roundToInt()
    val quote = quotes[(today.dayOfYear - 1) % quotes.size]
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text(today.format(DateTimeFormatter.ofPattern("M月d日 · EEEE", Locale.CHINA)), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(18.dp)) {
                    Text("每日一句", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text("“${quote.first}”", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 5.dp))
                    Text(quote.second, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("今日完成度", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f)); Text("$percent%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            LinearProgressIndicator(progress = { percent / 100f }, Modifier.fillMaxWidth().padding(top = 7.dp).height(9.dp).clip(MaterialTheme.shapes.small))
            Text("进行中按 50% 计入；完成 $points / ${todayTasks.size} 项", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
        item { Text("每日任务", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        if (todayTasks.isEmpty()) item { EmptyCard("今天还没有任务", "点击右下角 + 新建一个可坚持的小目标") }
        items(todayTasks.size) { index ->
            val task = todayTasks[index]
            TaskCard(task, onChange)
        }
    }
}

@Composable
private fun TaskCard(task: DailyTask, onChange: (DailyTask, ItemStatus) -> Unit) {
    ElevatedCard {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (task.status == ItemStatus.DONE) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null, tint = statusColor(task.status))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(task.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val detail = listOfNotNull(task.repeat.takeIf { it != RepeatRule.NONE }?.label, task.reminderTime?.let { "提醒 $it" }).joinToString(" · ")
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            StatusMenu(task.status) { onChange(task, it) }
        }
    }
}

@Composable
private fun PlansScreen(
    state: AppState,
    changePlan: (Plan, ItemStatus) -> Unit,
    changeGoal: (Plan, SubGoal, ItemStatus) -> Unit,
    saveReflection: (Plan, String) -> Unit,
    deletePlan: (Plan) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("计划表", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("长期目标拆成可完成的小阶段", style = MaterialTheme.typography.bodyMedium) }
        if (state.plans.isEmpty()) item { EmptyCard("还没有计划", "建立一个大计划，并为它设置完成时间和子目标") }
        items(state.plans.size) { index ->
            val plan = state.plans[index]
            PlanCard(plan, changePlan, changeGoal, saveReflection, deletePlan)
        }
    }
}

@Composable
private fun PlanCard(plan: Plan, changePlan: (Plan, ItemStatus) -> Unit, changeGoal: (Plan, SubGoal, ItemStatus) -> Unit, saveReflection: (Plan, String) -> Unit, deletePlan: (Plan) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    ElevatedCard {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(plan.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(plan.dueText + if (plan.isOverdue) " · 已逾期，仅提醒不改状态" else "", style = MaterialTheme.typography.bodySmall, color = if (plan.isOverdue) MaterialTheme.colorScheme.error else LocalContentColor.current)
                }
                StatusMenu(plan.calculatedStatus) { changePlan(plan, it) }
            }
            if (plan.subGoals.isNotEmpty()) {
                val progress = plan.subGoals.count { it.status == ItemStatus.DONE } / plan.subGoals.size.toFloat()
                LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().padding(top = 14.dp).height(7.dp).clip(MaterialTheme.shapes.small))
                Text("目标进度 ${(progress * 100).roundToInt()}% · ${plan.subGoals.count { it.status == ItemStatus.DONE }}/${plan.subGoals.size}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            TextButton(onClick = { expanded = !expanded }, Modifier.align(Alignment.End)) { Text(if (expanded) "收起详情" else "查看详情") }
            if (expanded) {
                plan.subGoals.forEach { goal -> Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) { Text(goal.title, Modifier.weight(1f)); StatusMenu(goal.status) { changeGoal(plan, goal, it) } } }
                ReflectionEditor(plan, saveReflection)
                TextButton(onClick = { confirmDelete = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Default.DeleteOutline, null); Spacer(Modifier.width(4.dp)); Text("删除计划（保留记录）") }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("软删除计划？") }, text = { Text("该计划将移到“删除记录”，可在设置中恢复，所有历史操作会保留。") }, confirmButton = { TextButton(onClick = { deletePlan(plan); confirmDelete = false }) { Text("删除") } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
}

@Composable
private fun ReflectionEditor(plan: Plan, save: (Plan, String) -> Unit) {
    var text by remember(plan.id) { mutableStateOf(plan.completionReflection) }
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("完成心得（可随时再编辑）") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        TextButton(onClick = { save(plan, text) }, Modifier.align(Alignment.End)) { Text("保存心得") }
    }
}

@Composable
private fun EmptyCard(title: String, subtitle: String) {
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp)) } }
}

@Composable
private fun CalendarScreen(state: AppState) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selected by remember { mutableStateOf(LocalDate.now()) }
    val days = remember(month) {
        val padding = month.atDay(1).dayOfWeek.value % 7
        List(padding) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    }
    val taskForDay: (LocalDate) -> List<DailyTask> = { day -> state.tasks.filter { it.date == day.toString() || it.repeat == RepeatRule.DAILY || (it.repeat == RepeatRule.WEEKLY && runCatching { LocalDate.parse(it.date).dayOfWeek == day.dayOfWeek }.getOrDefault(false)) }.map { it.copy(status = taskStatusOn(it, day, state)) } }
    val selectedTasks = taskForDay(selected)
    val logs = state.statusLogs.filter { it.at.startsWith(selected.toString()) }.sortedByDescending { it.at }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.Default.ChevronLeft, "上月") }
                Text(month.format(DateTimeFormatter.ofPattern("yyyy 年 M 月", Locale.CHINA)), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.Default.ChevronRight, "下月") }
            }
            Row(Modifier.fillMaxWidth()) { listOf("日", "一", "二", "三", "四", "五", "六").forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) } }
            LazyVerticalGrid(columns = GridCells.Fixed(7), modifier = Modifier.fillMaxWidth().height(290.dp), userScrollEnabled = false) {
                items(days) { day ->
                    if (day == null) Box(Modifier.aspectRatio(1f)) else {
                        val tasks = taskForDay(day)
                        val score = if (tasks.isEmpty()) null else tasks.sumOf { it.status.weight.toDouble() }.toFloat() / tasks.size
                        val dot = when { score == null -> Color.Transparent; score >= 1 -> statusColor(ItemStatus.DONE); score >= .5f -> statusColor(ItemStatus.IN_PROGRESS); else -> statusColor(ItemStatus.TODO) }
                        Column(Modifier.aspectRatio(1f).padding(2.dp).clip(MaterialTheme.shapes.small).background(if (day == selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent).clickable { selected = day }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text(day.dayOfMonth.toString(), fontWeight = if (day == LocalDate.now()) FontWeight.Bold else FontWeight.Normal)
                            Box(Modifier.padding(top = 2.dp).size(5.dp).clip(MaterialTheme.shapes.small).background(dot))
                        }
                    }
                }
            }
        }
        item { Text(selected.format(DateTimeFormatter.ofPattern("M 月 d 日", Locale.CHINA)) + " 的完成情况", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (selectedTasks.isEmpty() && logs.isEmpty()) item { EmptyCard("没有记录", "当天没有安排任务或状态变化") }
        items(selectedTasks) { task -> TaskCard(task) { _, _ -> } }
        if (logs.isNotEmpty()) item { Text("状态操作记录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
        items(logs) { log -> Text("${log.at.take(16).replace('T', ' ')} · ${log.itemType}「${log.title}」：${log.oldStatus.label} → ${log.newStatus.label}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 3.dp)) }
    }
}

@Composable
private fun FocusScreen(save: (FocusRecord) -> Unit, ring: () -> Unit) {
    var minutesText by rememberSaveable { mutableStateOf("25") }
    var remaining by rememberSaveable { mutableIntStateOf(25 * 60) }
    var total by rememberSaveable { mutableIntStateOf(25 * 60) }
    var running by rememberSaveable { mutableStateOf(false) }
    var askLeave by remember { mutableStateOf(false) }
    val formatted = "%02d:%02d".format(remaining / 60, remaining % 60)
    LaunchedEffect(running) {
        while (running && remaining > 0) { delay(1000); remaining-- }
        if (running && remaining == 0) { running = false; ring(); save(FocusRecord(title = "专注 $minutesText 分钟", startedAt = LocalDateTime.now().minusSeconds(total.toLong()).toString(), focusedSeconds = total, completed = true)) }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("专注模式", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("让这一段时间只属于眼前的一件事", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(30.dp))
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(250.dp)) { Box(contentAlignment = Alignment.Center) { Text(formatted, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold) } }
        Spacer(Modifier.height(24.dp))
        if (!running) {
            OutlinedTextField(value = minutesText, onValueChange = { value -> minutesText = value.filter(Char::isDigit).take(3) }, label = { Text("分钟（25 / 50 或自定义）") }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(25, 50).forEach { value -> OutlinedButton(onClick = { minutesText = value.toString(); total = value * 60; remaining = total }, Modifier.weight(1f)) { Text("$value 分钟") } } }
        }
        Spacer(Modifier.height(14.dp))
        Button(onClick = {
            if (running) running = false else { total = (minutesText.toIntOrNull() ?: 25).coerceIn(1, 180) * 60; if (remaining == 0 || remaining > total) remaining = total; running = true }
        }, Modifier.fillMaxWidth()) { Icon(if (running) Icons.Default.Pause else Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (running) "暂停" else if (remaining < total) "继续专注" else "开始专注") }
        if (running || remaining < total) TextButton(onClick = { askLeave = true }) { Text("结束本次专注") }
    }
    if (askLeave) AlertDialog(onDismissRequest = { askLeave = false }, title = { Text("您真的确定要离开吗？") }, text = { Text("当前已专注 ${(total - remaining) / 60} 分钟，暂停时间不会计入记录。") }, confirmButton = { TextButton(onClick = { running = false; save(FocusRecord(title = "专注 $minutesText 分钟", startedAt = LocalDateTime.now().minusSeconds((total - remaining).toLong()).toString(), focusedSeconds = total - remaining, completed = false)); askLeave = false; remaining = total }) { Text("结束并保存") } }, dismissButton = { TextButton(onClick = { askLeave = false }) { Text("继续专注") } })
}

@Composable
private fun SettingsScreen(state: AppState, controller: ZiluController, context: Context) {
    var recordMode by remember { mutableStateOf("设置") }
    var restoreMessage by remember { mutableStateOf<String?>(null) }
    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        controller.saveSettings(state.settings.copy(soundUri = uri.toString(), soundName = "已导入音频"))
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.openOutputStream(uri)?.use { it.write(controller.export().toByteArray()) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val result = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()?.let { controller.restore(it).getOrThrow() } ?: error("文件为空") }
        restoreMessage = if (result.isSuccess) "备份已恢复" else "恢复失败：请确认选择的是自律备份文件"
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("设置", "总结", "完成记录", "删除记录").forEachIndexed { index, label -> SegmentedButton(selected = recordMode == label, onClick = { recordMode = label }, shape = SegmentedButtonDefaults.itemShape(index, 4)) { Text(label, maxLines = 1) } }
            }
        }
        when (recordMode) {
            "设置" -> {
                item { AppearanceCard(state.settings, controller::saveSettings) }
                item { SoundCard(state.settings, { soundPicker.launch(arrayOf("audio/*")) }, context) }
                item { BackupCard({ exportLauncher.launch("zilu-backup.json") }, { importLauncher.launch(arrayOf("*/*")) }) }
                item { restoreMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) } }
            }
            "总结" -> item { SummaryEditor(state, controller::saveSummary) }
            "完成记录" -> item { CompletionHistory(state) }
            "删除记录" -> item { DeletedPlans(state.deletedPlans, controller::restorePlan) }
        }
    }
}

@Composable
private fun AppearanceCard(settings: AppSettings, save: (AppSettings) -> Unit) {
    ElevatedCard { Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("外观", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("深色模式", Modifier.weight(1f)); Switch(checked = settings.darkMode, onCheckedChange = { save(settings.copy(darkMode = it)) }) }
        Text("字体大小", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(.9f to "小", 1f to "标准", 1.15f to "大").forEach { (scale, label) -> FilterChip(selected = settings.fontScale == scale, onClick = { save(settings.copy(fontScale = scale)) }, label = { Text(label) }) } }
        Text("主题颜色", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("琥珀金", "松石绿", "暮光紫").forEach { name -> FilterChip(selected = settings.themeName == name, onClick = { save(settings.copy(themeName = name)) }, label = { Text(name) }) } }
    } }
}

@Composable
private fun SoundCard(settings: AppSettings, pickSound: () -> Unit, context: Context) {
    ElevatedCard { Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("完成提示音", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("仅首次切换为“已完成”时播放", style = MaterialTheme.typography.bodySmall)
        Text(settings.soundName, modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TextButton(onClick = { playCompletionSound(context, settings) }) { Text("试听") }; TextButton(onClick = pickSound) { Icon(Icons.Default.AudioFile, null); Text("导入手机音频") } }
    } }
}

@Composable
private fun BackupCard(onExport: () -> Unit, onImport: () -> Unit) {
    ElevatedCard { Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("数据保存与迁移", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("所有修改已自动保存在本机。导出备份后，可在新手机或重新安装后恢复。", style = MaterialTheme.typography.bodySmall)
        Row { TextButton(onClick = onExport) { Icon(Icons.Default.Upload, null); Text("导出备份") }; TextButton(onClick = onImport) { Icon(Icons.Default.Download, null); Text("恢复备份") } }
    } }
}

@Composable
private fun SummaryEditor(state: AppState, save: (DailySummary) -> Unit) {
    val today = LocalDate.now().toString()
    val existing = state.summaries.firstOrNull { it.date == today } ?: DailySummary(today)
    var completed by remember(existing.updatedAt) { mutableStateOf(existing.completed) }
    var improvement by remember(existing.updatedAt) { mutableStateOf(existing.improvement) }
    var tomorrow by remember(existing.updatedAt) { mutableStateOf(existing.tomorrow) }
    ElevatedCard { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("每日总结 · ${LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日", Locale.CHINA))}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(completed, { completed = it }, label = { Text("今天完成了什么") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        OutlinedTextField(improvement, { improvement = it }, label = { Text("有哪些不足") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        OutlinedTextField(tomorrow, { tomorrow = it }, label = { Text("明天准备做什么") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Button(onClick = { save(DailySummary(today, completed, improvement, tomorrow)) }, Modifier.align(Alignment.End)) { Text("保存总结") }
    } }
}

@Composable
private fun CompletionHistory(state: AppState) {
    var filter by remember { mutableStateOf("") }
    val logs = state.statusLogs.filter { it.newStatus == ItemStatus.DONE && (filter.isBlank() || it.at.startsWith(filter)) }.sortedByDescending { it.at }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(filter, { filter = it }, label = { Text("按日期筛选（例：2026-11-21）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text("已完成记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (logs.isEmpty()) EmptyCard("没有符合条件的完成记录", "可输入日期查看当天任务和计划完成情况")
        logs.forEach { Text("${it.at.take(16).replace('T', ' ')} · ${it.itemType}「${it.title}」", Modifier.padding(vertical = 4.dp)) }
    }
}

@Composable
private fun DeletedPlans(plans: List<Plan>, restore: (Plan) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("已删除计划", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (plans.isEmpty()) EmptyCard("删除记录为空", "计划删除后会出现在这里，而不是永久消失")
        plans.sortedByDescending { it.deletedAt }.forEach { plan -> ElevatedCard { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(plan.title, fontWeight = FontWeight.SemiBold); Text("删除于 ${plan.deletedAt?.take(16)?.replace('T', ' ')}", style = MaterialTheme.typography.bodySmall) }; TextButton(onClick = { restore(plan) }) { Text("恢复") } } } }
    }
}

@Composable
private fun TaskDialog(onDismiss: () -> Unit, onSave: (String, RepeatRule, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var reminder by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf(RepeatRule.NONE) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("新建每日任务") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(title, { title = it }, label = { Text("任务名称") }, modifier = Modifier.fillMaxWidth())
        Text("重复方式", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { RepeatRule.entries.forEach { rule -> FilterChip(selected = repeat == rule, onClick = { repeat = rule }, label = { Text(rule.label) }) } }
        OutlinedTextField(reminder, { reminder = it }, label = { Text("提醒时间（HH:mm，可不填）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    } }, confirmButton = { TextButton(onClick = { onSave(title, repeat, reminder) }, enabled = title.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun PlanDialog(onDismiss: () -> Unit, onSave: (String, String, String, List<String>, String) -> Unit) {
    var title by remember { mutableStateOf("") }; var dueText by remember { mutableStateOf("") }; var dueDate by remember { mutableStateOf("") }; var goals by remember { mutableStateOf("") }; var reminder by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("新建计划") }, text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.heightIn(max = 430.dp)) {
        item { OutlinedTextField(title, { title = it }, label = { Text("大计划名称") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(dueText, { dueText = it }, label = { Text("完成时间说明（可自行修改）") }, placeholder = { Text("例：预计 12 月完成") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(dueDate, { dueDate = it }, label = { Text("截止日期（yyyy-MM-dd，可不填）") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item { OutlinedTextField(goals, { goals = it }, label = { Text("子目标（每行一个）") }, placeholder = { Text("原型\nUI\n开发\n测试") }, modifier = Modifier.fillMaxWidth(), minLines = 3) }
        item { OutlinedTextField(reminder, { reminder = it }, label = { Text("提醒时间（HH:mm，可不填）") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
    } }, confirmButton = { TextButton(onClick = { onSave(title, dueText, dueDate, goals.lines(), reminder) }, enabled = title.isNotBlank()) { Text("建立计划") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
