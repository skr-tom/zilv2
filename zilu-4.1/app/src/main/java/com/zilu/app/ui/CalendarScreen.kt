package com.zilu.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.zilu.app.AppState
import com.zilu.app.TodoItem
import com.zilu.app.todosOf
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val weekLabels = listOf("一", "二", "三", "四", "五", "六", "日")

@Composable
fun CalendarScreen(
    state: AppState,
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit,
    onToggle: (String) -> Unit,
    onDelete: (String) -> Unit,
    onReorder: (LocalDate, List<String>) -> Unit
) {
    var month by remember { mutableStateOf(YearMonth.from(selected)) }
    var showMonthPicker by remember { mutableStateOf(false) }

    LaunchedEffect(selected) {
        val target = YearMonth.from(selected)
        if (target != month) month = target
    }

    val todos = todosOf(state, selected)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            CalendarPanel(
                state = state,
                month = month,
                selected = selected,
                onMonthChange = { month = it },
                onSelect = onSelect,
                onOpenPicker = { showMonthPicker = true }
            )
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    selected.format(DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)) + " 待办事项",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (selected != LocalDate.now()) {
                    TextButton(onClick = { onSelect(LocalDate.now()) }) { Text("回到今天") }
                }
            }
        }

        item {
            if (todos.isEmpty()) {
                EmptyHint("这一天还没有待办，点右下角 + 添加")
            } else {
                TodoReorderList(
                    items = todos,
                    onToggle = onToggle,
                    onDelete = onDelete,
                    onCommit = { order -> onReorder(selected, order) }
                )
            }
        }

        if (todos.size > 1) {
            item {
                Text(
                    "拖动左侧色条可以上下调整顺序；勾选完成后会自动排到最下面",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }

    if (showMonthPicker) {
        MonthPickerDialog(
            month = month,
            onDismiss = { showMonthPicker = false },
            onConfirm = {
                month = it
                showMonthPicker = false
            }
        )
    }
}

@Composable
private fun CalendarPanel(
    state: AppState,
    month: YearMonth,
    selected: LocalDate,
    onMonthChange: (YearMonth) -> Unit,
    onSelect: (LocalDate) -> Unit,
    onOpenPicker: () -> Unit
) {
    val today = LocalDate.now()
    val firstOfMonth = month.atDay(1)
    // 周一作为一周的第一天，和参考图一致
    val leading = Math.floorMod(firstOfMonth.dayOfWeek.value - 1, 7)
    val startDate = firstOfMonth.minusDays(leading.toLong())
    val cells = (0 until 42).map { startDate.plusDays(it.toLong()) }

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            Modifier.background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    )
                )
            )
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "待办事项",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onMonthChange(month.minusMonths(1)) }) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "上个月")
                    }
                    Text(
                        month.format(DateTimeFormatter.ofPattern("yyyy.MM", Locale.CHINA)),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(onClick = { onMonthChange(month.plusMonths(1)) }) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "下个月")
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenPicker() }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        selected.format(DateTimeFormatter.ofPattern("yyyy-MM-dd EEEE", Locale.CHINA)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("  ▾", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)) {
                    weekLabels.forEach {
                        Text(
                            it,
                            Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                cells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { day ->
                            DayCell(
                                day = day,
                                month = month,
                                today = today,
                                selected = selected,
                                state = state,
                                onSelect = onSelect,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: LocalDate,
    month: YearMonth,
    today: LocalDate,
    selected: LocalDate,
    state: AppState,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    val inMonth = YearMonth.from(day) == month
    val todos = todosOf(state, day)
    val isToday = day == today
    val isSelected = day == selected
    val bg = when {
        isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        todos.isNotEmpty() -> {
            val allDone = todos.all { it.done }
            (if (allDone) DoneGreen else UndoneRed).copy(alpha = 0.08f)
        }
        else -> Color.Transparent
    }
    val numberColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        isToday -> UndoneRed
        !inMonth -> MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier
            // 一格要放得下日期数字 + 3 条待办标签，之前的 54dp 会把第三条裁掉
            .height(68.dp)
            .padding(1.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable { onSelect(day) }
            .padding(top = 3.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            day.dayOfMonth.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
            color = numberColor
        )
        if (todos.isNotEmpty()) {
            todos.take(3).forEach { todo ->
                val tone = todoTone(todo.colorIndex)
                Text(
                    todo.title,
                    fontSize = 7.sp,
                    lineHeight = 8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = tone.accent,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 1.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(tone.accent.copy(alpha = 0.12f))
                        .padding(vertical = 0.5.dp)
                )
            }
            if (todos.size > 3) {
                Text(
                    "+${todos.size - 3} 更多",
                    fontSize = 7.sp,
                    lineHeight = 9.sp,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 可拖动排序的待办列表。
 * 左侧色条是专门的拖动把手，标题长按可删除，避免和拖动冲突。
 */
@Composable
private fun TodoReorderList(
    items: List<TodoItem>,
    onToggle: (String) -> Unit,
    onDelete: (String) -> Unit,
    onCommit: (List<String>) -> Unit
) {
    val rowPitch = 60.dp
    val rowPx = with(LocalDensity.current) { rowPitch.toPx() }

    // 拖动过程中列表顺序始终不变，只有「视觉位移」在变。
    // 旧实现边拖边重排 order，重组会打断 pointerInput 并让相邻卡片叠在一起（图 2 圈出的问题）。
    var dragId by remember { mutableStateOf<String?>(null) }
    var fromIndex by remember { mutableIntStateOf(-1) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var pendingDelete by remember { mutableStateOf<TodoItem?>(null) }

    val latestCommit by rememberUpdatedState(onCommit)
    val latestToggle by rememberUpdatedState(onToggle)
    val latestDelete by rememberUpdatedState(onDelete)
    val latestItems by rememberUpdatedState(items)

    val targetIndex = if (dragId == null || fromIndex < 0) -1
    else (fromIndex + (dragY / rowPx).roundToInt())
        .coerceIn(0, items.lastIndex.coerceAtLeast(0))

    Column {
        items.forEachIndexed { index, item ->
            val isDragging = dragId == item.id
            // 被拖的跟手走，其它卡片整行平移让出空位，不会互相覆盖
            val shift = when {
                dragId == null || targetIndex < 0 || fromIndex < 0 -> 0f
                isDragging -> dragY
                fromIndex < targetIndex && index in (fromIndex + 1)..targetIndex -> -rowPx
                fromIndex > targetIndex && index in targetIndex until fromIndex -> rowPx
                else -> 0f
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(rowPitch)
                    .zIndex(if (isDragging) 1f else 0f)
                    .offset { IntOffset(0, shift.roundToInt()) }
                    .padding(vertical = 4.dp)
            ) {
                TodoRow(
                    item = item,
                    elevated = isDragging,
                    onToggle = { latestToggle(item.id) },
                    onLongPress = { pendingDelete = item },
                    dragHandle = Modifier.pointerInput(item.id, index) {
                        detectDragGestures(
                            onDragStart = {
                                dragId = item.id
                                fromIndex = index
                                dragY = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragY += amount.y
                            },
                            onDragEnd = {
                                val current = latestItems
                                val to = (fromIndex + (dragY / rowPx).roundToInt())
                                    .coerceIn(0, current.lastIndex.coerceAtLeast(0))
                                val ids = current.map { it.id }.toMutableList()
                                if (fromIndex in ids.indices && to in ids.indices && fromIndex != to) {
                                    val moved = ids.removeAt(fromIndex)
                                    ids.add(to, moved)
                                    latestCommit(ids)
                                }
                                dragId = null
                                fromIndex = -1
                                dragY = 0f
                            },
                            onDragCancel = {
                                dragId = null
                                fromIndex = -1
                                dragY = 0f
                            }
                        )
                    }
                )
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条待办？") },
            text = { Text("「${target.title}」") },
            confirmButton = {
                TextButton(onClick = {
                    latestDelete(target.id)
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun TodoRow(
    item: TodoItem,
    elevated: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    dragHandle: Modifier
) {
    val tone = todoTone(item.colorIndex)
    val shape = RoundedCornerShape(14.dp)
    Surface(
        shape = shape,
        color = tone.container,
        shadowElevation = if (elevated) 8.dp else 0.dp,
        tonalElevation = if (elevated) 4.dp else 0.dp,
        modifier = Modifier.fillMaxSize()
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(32.dp)
                    .fillMaxHeight()
                    .then(dragHandle),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(width = 4.dp, height = 26.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(tone.accent)
                )
            }
            Text(
                item.title,
                Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
                    .pointerInput(item.id) {
                        detectTapGestures(onLongPress = { onLongPress() })
                    },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = if (item.done) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
            )
            Box(
                Modifier
                    .padding(end = 14.dp)
                    .size(24.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .border(
                        width = 1.5.dp,
                        color = if (item.done) DoneGreen else tone.accent.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(7.dp)
                    )
                    .clickable { onToggle() },
                contentAlignment = Alignment.Center
            ) {
                if (item.done) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "已完成",
                        tint = DoneGreen,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthPickerDialog(
    month: YearMonth,
    onDismiss: () -> Unit,
    onConfirm: (YearMonth) -> Unit
) {
    var year by remember(month) { mutableStateOf(month.year) }
    var monthValue by remember(month) { mutableStateOf(month.monthValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择年月") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumberWheel(
                        value = year,
                        range = LocalDate.now().year - 5..LocalDate.now().year + 10,
                        onValue = { year = it },
                        modifier = Modifier.weight(1f)
                    )
                    Text("年")
                    NumberWheel(
                        value = monthValue,
                        range = 1..12,
                        onValue = { monthValue = it },
                        modifier = Modifier.weight(1f)
                    )
                    Text("月")
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = {
                        val now = LocalDate.now()
                        year = now.year
                        monthValue = now.monthValue
                    }) { Text("本月") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(YearMonth.of(year, monthValue)) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
