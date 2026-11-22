@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.zilu.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.media.RingtoneManager
import android.widget.NumberPicker
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); enableEdgeToEdge(); setContent { ZiluApp() } }
}

private class ZiluController(private val context: Context) {
    private val repo = ZiluRepository(context)
    var state by mutableStateOf(repo.load()); private set
    fun refresh() { state = repo.load() }
    private fun save(next: AppState) { state = next; repo.save(next) }
    fun addTask(title: String, minutes: Int, reminder: String?) {
        val task = DailyTask(title = title.trim(), minutes = minutes.coerceIn(1, 240), reminderTime = reminder)
        save(state.copy(tasks = state.tasks + task)); ReminderScheduler.schedule(context, task.id, task.title, reminder)
    }
    fun startTask(task: DailyTask) { val session = TaskTimerService.start(context, task); save(state.copy(activeFocus = session)) }
    fun addPlan(title: String, date: String, goalNames: List<String>) = save(state.copy(plans = state.plans + Plan(title = title.trim(), dueDate = date, subGoals = goalNames.filter { it.isNotBlank() }.map { SubGoal(title = it.trim()) }.ifEmpty { listOf(SubGoal(title = "子目标 1")) })))
    fun cycleGoal(plan: Plan, goal: SubGoal) {
        val next = when (goal.status) { ItemStatus.TODO -> ItemStatus.IN_PROGRESS; ItemStatus.IN_PROGRESS -> ItemStatus.DONE; ItemStatus.DONE -> ItemStatus.TODO }
        val changed = plan.copy(subGoals = plan.subGoals.map { if (it.id == goal.id) goal.copy(status = next) else it })
        save(state.copy(plans = state.plans.map { if (it.id == plan.id) changed else it }))
        if (next == ItemStatus.DONE) RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play()
    }
    fun addGoal(plan: Plan) { val changed = plan.copy(subGoals = plan.subGoals + SubGoal(title = "子目标 ${plan.subGoals.size + 1}")); save(state.copy(plans = state.plans.map { if (it.id == plan.id) changed else it })) }
    fun saveReflection(plan: Plan, value: String) { save(state.copy(plans = state.plans.map { if (it.id == plan.id) it.copy(completionReflection = value) else it })) }
    fun saveNote(date: LocalDate, content: String) { save(state.copy(notes = state.notes.filterNot { it.date == date.toString() } + DailyNote(date.toString(), content))) }
    fun saveSettings(settings: AppSettings) = save(state.copy(settings = settings))
    fun enterOffline() = save(state.copy(account = UserAccount("本地", "本地模式")))
}

@Composable
private fun ZiluApp() {
    val context = LocalContext.current
    val controller = remember { ZiluController(context.applicationContext) }
    val state = controller.state
    val density = LocalDensity.current
    var page by rememberSaveable { mutableIntStateOf(0) }
    var addTask by remember { mutableStateOf(false) }; var addPlan by remember { mutableStateOf(false) }
    var askNotification by rememberSaveable { mutableStateOf(true) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { askNotification = false }
    LaunchedEffect(Unit) { while (true) { delay(1000); controller.refresh() } }
    val primary = when (state.settings.themeName) { "松石绿" -> Color(0xFF006B5B); "暮光紫" -> Color(0xFF6950A1); else -> Color(0xFF875E12) }
    CompositionLocalProvider(LocalDensity provides Density(density.density, state.settings.fontScale)) { MaterialTheme(colorScheme = if (state.settings.darkMode) darkColorScheme(primary = primary) else lightColorScheme(primary = primary, tertiary = Color(0xFF23865A))) {
        if (state.account == null) WelcomeScreen(onOffline = controller::enterOffline) else if (state.activeFocus != null) FocusOverlay(state.activeFocus) else {
            Scaffold(
                topBar = { TopAppBar(title = { Text("自律", fontWeight = FontWeight.Bold) }) },
                bottomBar = { NavigationBar { listOf("任务" to Icons.Default.Today, "计划" to Icons.Default.Flag, "日历" to Icons.Default.CalendarMonth, "小记" to Icons.Default.EditNote, "设置" to Icons.Default.Settings).forEachIndexed { index, pair -> NavigationBarItem(selected = page == index, onClick = { page = index }, icon = { Icon(pair.second, pair.first) }, label = { Text(pair.first) }) } } },
                floatingActionButton = { if (page < 2) FloatingActionButton(onClick = { if (page == 0) addTask = true else addPlan = true }) { Icon(Icons.Default.Add, "新增") } }
            ) { padding -> Box(Modifier.padding(padding)) { when (page) { 0 -> TasksScreen(state, controller::startTask); 1 -> PlansScreen(state, controller::cycleGoal, controller::addGoal, controller::saveReflection); 2 -> CalendarScreen(state); 3 -> NotesScreen(state, controller::saveNote); else -> SettingsScreen(state, controller::saveSettings, context) } } }
        }
        if (askNotification && state.account != null) NotificationPrompt(onAllow = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }, onDismiss = { askNotification = false })
        if (addTask) TaskDialog({ addTask = false }) { t, m, r -> controller.addTask(t, m, r); addTask = false }
        if (addPlan) PlanDialog({ addPlan = false }) { t, d, goals -> controller.addPlan(t, d, goals); addPlan = false }
    } }
}

@Composable private fun NotificationPrompt(onAllow: () -> Unit, onDismiss: () -> Unit) = AlertDialog(onDismissRequest = onDismiss, title = { Text("打开通知？") }, text = { Text("提醒和倒计时会显示在手机通知栏。") }, confirmButton = { TextButton(onClick = onAllow) { Text("打开") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("暂不") } })

@Composable
private fun WelcomeScreen(onOffline: () -> Unit) {
    var notice by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(34.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(painterResource(R.drawable.zilu_icon), null, Modifier.size(116.dp), tint = Color.Unspecified)
        Spacer(Modifier.height(20.dp)); Text("自律，做强大的自己", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Discipline · Become a stronger self", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(42.dp))
        Button(onClick = { notice = true }, Modifier.fillMaxWidth()) { Text("微信登录") }
        OutlinedButton(onClick = { notice = true }, Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("QQ 登录") }
        TextButton(onClick = onOffline) { Text("进入本地模式") }
    }
    if (notice) AlertDialog(onDismissRequest = { notice = false }, title = { Text("第三方登录待配置") }, text = { Text("接入微信或 QQ 授权前，需要配置对应开放平台的 App ID、签名和回调地址。") }, confirmButton = { TextButton(onClick = { notice = false }) { Text("知道了") } })
}

private fun isComplete(task: DailyTask, day: LocalDate, state: AppState) = state.completionLogs.any { it.taskId == task.id && it.date == day.toString() }
private fun tasksFor(day: LocalDate, state: AppState) = state.tasks.filter { runCatching { !LocalDate.parse(it.createdDate).isAfter(day) }.getOrDefault(false) }
private fun redGreen(done: Boolean) = if (done) Color(0xFF23865A) else Color(0xFFBE4B4B)
private fun streak(task: DailyTask, state: AppState): Int { var day = LocalDate.now(); var count = 0; while (state.completionLogs.any { it.taskId == task.id && it.date == day.toString() }) { count++; day = day.minusDays(1) }; return count }

@Composable
private fun TasksScreen(state: AppState, onStart: (DailyTask) -> Unit) {
    val today = LocalDate.now(); val tasks = tasksFor(today, state); val done = tasks.count { isComplete(it, today, state) }; val progress = if (tasks.isEmpty()) 0 else (done * 100f / tasks.size).roundToInt()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(today.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)), style = MaterialTheme.typography.titleMedium); Text("今日完成度 $progress%", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); LinearProgressIndicator({ progress / 100f }, Modifier.fillMaxWidth().height(8.dp).clip(MaterialTheme.shapes.small)) }
        items(tasks.size) { index -> TaskCard(tasks[index], isComplete(tasks[index], today, state), streak(tasks[index], state), onStart) }
    }
}

@Composable
private fun TaskCard(task: DailyTask, done: Boolean, days: Int, onStart: (DailyTask) -> Unit) {
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = redGreen(done).copy(alpha = .12f))) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null, tint = redGreen(done)); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(task.title, fontWeight = FontWeight.Bold); Text("${task.minutes} 分钟" + (task.reminderTime?.let { " · $it 提醒" } ?: ""), style = MaterialTheme.typography.bodySmall); if (days > 0) Text("已坚持 $days 天", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall) }
        if (done) Text("已完成", color = redGreen(true), fontWeight = FontWeight.Bold) else Button(onClick = { onStart(task) }) { Icon(Icons.Default.PlayArrow, null); Text("开始") }
    } }
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
private fun CalendarScreen(state: AppState) {
    var month by remember { mutableStateOf(YearMonth.now()) }; var selected by remember { mutableStateOf(LocalDate.now()) }
    val firstPadding = month.atDay(1).dayOfWeek.value % 7
    val cells: List<LocalDate?> = List(firstPadding) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    val selectedTasks = tasksFor(selected, state)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.Default.ChevronLeft, null) }; Text(month.format(DateTimeFormatter.ofPattern("yyyy 年 M 月", Locale.CHINA)), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center); IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.Default.ChevronRight, null) } } }
        item { Row(Modifier.fillMaxWidth()) { listOf("日", "一", "二", "三", "四", "五", "六").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall) } } }
        item { LazyVerticalGrid(GridCells.Fixed(7), Modifier.fillMaxWidth().height(300.dp), userScrollEnabled = false) { items(cells) { day -> if (day == null) Box(Modifier.aspectRatio(1f)) else { val tasks = tasksFor(day, state); val enabled = tasks.isNotEmpty(); val finished = enabled && tasks.all { isComplete(it, day, state) }; val bg = if (!enabled) Color.Transparent else redGreen(finished).copy(alpha = .22f); Box(Modifier.aspectRatio(1f).padding(2.dp).clip(MaterialTheme.shapes.small).background(if (day == selected) redGreen(finished).copy(alpha = .35f) else bg).clickable { selected = day }, contentAlignment = Alignment.Center) { Text(day.dayOfMonth.toString(), color = if (!enabled) MaterialTheme.colorScheme.outline else LocalContentColor.current) } } } } }
        item { Text(selected.format(DateTimeFormatter.ofPattern("M 月 d 日待办事项", Locale.CHINA)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (selectedTasks.isEmpty()) item { Text("", Modifier.height(1.dp)) }
        items(selectedTasks.size) { i -> val task = selectedTasks[i]; val done = isComplete(task, selected, state); Surface(color = redGreen(done).copy(alpha = .12f), shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null, tint = redGreen(done)); Spacer(Modifier.width(8.dp)); Text(task.title, Modifier.weight(1f)); Text("${task.minutes} 分钟", style = MaterialTheme.typography.bodySmall) } } }
    }
}

@Composable
private fun NotesScreen(state: AppState, save: (LocalDate, String) -> Unit) {
    var selected by remember { mutableStateOf(LocalDate.now()) }
    val existing = state.notes.firstOrNull { it.date == selected.toString() }
    var text by remember(selected, existing?.updatedAt) { mutableStateOf(existing?.content.orEmpty()) }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("每日小记", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        DateWheel(selected) { selected = it }
        OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().weight(1f), label = { Text("日记") })
        Button(onClick = { save(selected, text) }, Modifier.fillMaxWidth()) { Text("保存") }
    }
}

@Composable
private fun SettingsScreen(state: AppState, save: (AppSettings) -> Unit, context: Context) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("深色模式", Modifier.weight(1f)); Switch(state.settings.darkMode, { save(state.settings.copy(darkMode = it)) }) }
        Text("字体大小 ${(state.settings.fontScale * 100).roundToInt()}%")
        Slider(value = state.settings.fontScale, onValueChange = { save(state.settings.copy(fontScale = it)) }, valueRange = .85f..1.3f)
        Text("主题颜色")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("琥珀金", "松石绿", "暮光紫").forEach { name -> FilterChip(selected = state.settings.themeName == name, onClick = { save(state.settings.copy(themeName = name)) }, label = { Text(name) }) } }
        Spacer(Modifier.weight(1f))
        Button(onClick = { shareInstalledApp(context) }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text("分享给微信 / QQ 好友") }
    }
}

private fun shareInstalledApp(context: Context) {
    runCatching {
        val source = File(context.applicationInfo.sourceDir); val copy = File(context.cacheDir, "zilu.apk"); source.copyTo(copy, overwrite = true)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", copy)
        val send = Intent(Intent.ACTION_SEND).setType("application/vnd.android.package-archive").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "分享自律"))
    }
}

@Composable
private fun FocusOverlay(session: FocusSession) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session.endAtMillis) { while (now < session.endAtMillis) { delay(1000); now = System.currentTimeMillis() } }
    val remain = (session.endAtMillis - now).coerceAtLeast(0L); val clock = "%02d:%02d".format(remain / 60000, (remain / 1000) % 60)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text("专注中", style = MaterialTheme.typography.headlineSmall); Text(session.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)); Text(clock, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 32.dp)); Text("倒计时已显示在通知栏", style = MaterialTheme.typography.bodyMedium) } }
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
    var year by remember(value) { mutableIntStateOf(value.year) }; var month by remember(value) { mutableIntStateOf(value.monthValue) }; var day by remember(value) { mutableIntStateOf(value.dayOfMonth) }
    LaunchedEffect(year, month, day) { onValue(runCatching { LocalDate.of(year, month, day) }.getOrElse { LocalDate.of(year, month, 1) }) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { WheelNumber(year, LocalDate.now().year - 5..LocalDate.now().year + 10, { year = it }, Modifier.weight(1f)); Text("年"); WheelNumber(month, 1..12, { month = it }, Modifier.weight(1f)); Text("月"); WheelNumber(day, 1..31, { day = it }, Modifier.weight(1f)); Text("日") }
}

@Composable
private fun TaskDialog(dismiss: () -> Unit, save: (String, Int, String?) -> Unit) {
    var title by remember { mutableStateOf("") }; var minutes by remember { mutableStateOf("25") }; var reminder by remember { mutableStateOf("20:00") }; var enabled by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("新增每日任务") }, text = { LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { item { OutlinedTextField(title, { title = it }, label = { Text("任务") }, modifier = Modifier.fillMaxWidth()) }; item { OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(3) }, label = { Text("时长（分钟）") }, modifier = Modifier.fillMaxWidth()) }; item { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(enabled, { enabled = it }); Text("设置提醒") } }; if (enabled) item { TimeWheel(reminder) { reminder = it } } } }, confirmButton = { TextButton(onClick = { save(title, minutes.toIntOrNull() ?: 25, reminder.takeIf { enabled }) }, enabled = title.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable
private fun PlanDialog(dismiss: () -> Unit, save: (String, String, List<String>) -> Unit) {
    var title by remember { mutableStateOf("") }; var date by remember { mutableStateOf(LocalDate.now().plusMonths(1)) }; var goals by remember { mutableStateOf(listOf("子目标 1")) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("新增长期计划") }, text = { LazyColumn(Modifier.heightIn(max = 470.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { item { OutlinedTextField(title, { title = it }, label = { Text("计划") }, modifier = Modifier.fillMaxWidth()) }; item { Text("截止时间") }; item { DateWheel(date) { date = it } }; items(goals.size) { i -> OutlinedTextField(goals[i], { value -> goals = goals.toMutableList().also { it[i] = value } }, label = { Text("子目标 ${i + 1}") }, modifier = Modifier.fillMaxWidth()) }; item { TextButton(onClick = { goals = goals + "" }) { Icon(Icons.Default.Add, null); Text("添加子目标") } } } }, confirmButton = { TextButton(onClick = { save(title, date.toString(), goals) }, enabled = title.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
