package com.zilu.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zilu.app.AppState
import com.zilu.app.DailyTask
import com.zilu.app.Quote
import com.zilu.app.TaskRepeat
import com.zilu.app.formatLeft
import com.zilu.app.quoteOfDay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

fun isTaskComplete(task: DailyTask, day: LocalDate, state: AppState): Boolean =
    state.completionLogs.any { it.taskId == task.id && it.date == day.toString() }

fun weekName(day: Int): String = listOf("一", "二", "三", "四", "五", "六", "日")[(day - 1).coerceIn(0, 6)]

fun repeatLabel(task: DailyTask): String = when (task.repeat) {
    TaskRepeat.DAILY -> "每天"
    TaskRepeat.WEEKLY -> "每周" + task.activeWeekDays.sorted().joinToString("") { weekName(it) }
    TaskRepeat.ONCE -> "仅 ${task.createdDate}"
}

/** 连续完成天数：跳过不执行的日子。 */
fun streak(task: DailyTask, state: AppState): Int {
    val today = LocalDate.now()
    var count = 0
    for (i in 0..400) {
        val day = today.minusDays(i.toLong())
        if (!task.occursOn(day)) continue
        if (isTaskComplete(task, day, state)) count++ else if (day != today) break
    }
    return count
}

@Composable
fun TasksScreen(
    state: AppState,
    onStart: (DailyTask) -> Unit,
    onEdit: (DailyTask) -> Unit
) {
    val today = LocalDate.now()
    val todayTasks = state.tasks.filter { it.occursOn(today) }.sortedBy { it.sortOrder }
    val otherTasks = state.tasks.filterNot { it.occursOn(today) }
    val doneCount = todayTasks.count { isTaskComplete(it, today, state) }
    val progress = if (todayTasks.isEmpty()) 0f else doneCount.toFloat() / todayTasks.size

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { QuoteCard(quoteOfDay(today), today) }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    today.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${(progress * 100).roundToInt()}%",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "今日完成度  $doneCount / ${todayTasks.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(50)),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }

        if (todayTasks.isEmpty()) {
            item { EmptyHint("今天还没有任务，点右下角 + 新增一个") }
        }

        items(todayTasks, key = { it.id }) { task ->
            TaskCard(
                task = task,
                done = isTaskComplete(task, today, state),
                days = streak(task, state),
                activeToday = true,
                onStart = onStart,
                onEdit = onEdit
            )
        }

        if (otherTasks.isNotEmpty()) {
            item {
                Text(
                    "今天不执行的任务",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            items(otherTasks, key = { it.id }) { task ->
                TaskCard(task, false, 0, false, onStart, onEdit)
            }
        }
    }
}

@Composable
private fun QuoteCard(quote: Quote, day: LocalDate) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            Modifier
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                )
                .padding(18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // 明确标出是哪一天、以及这是一条中英双语名言，每天自动更换
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "每日一句 · " + day.format(DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "EN / 中文",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(quote.en, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
                Text(quote.zh, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "— ${quote.author}",
                    Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: DailyTask,
    done: Boolean,
    days: Int,
    activeToday: Boolean,
    onStart: (DailyTask) -> Unit,
    onEdit: (DailyTask) -> Unit
) {
    val tint = when {
        !activeToday -> MaterialTheme.colorScheme.outline
        done -> DoneGreen
        else -> UndoneRed
    }
    ElevatedCard(
        colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
            containerColor = tint.copy(alpha = 0.09f)
        )
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = tint
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(task.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                val meta = buildString {
                    append("计划 ${task.planMinutes} 分钟")
                    append(" · ").append(repeatLabel(task))
                    task.reminderTime?.let { append(" · ").append(it).append(" 截止") }
                }
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (task.isPartial && !done) {
                    Text(
                        "已做一部分，还剩 ${formatLeft(task.leftSeconds)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    LinearProgressIndicator(
                        progress = { 1f - task.leftSeconds.toFloat() / task.durationSeconds.coerceAtLeast(1) },
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .fillMaxWidth(0.7f)
                            .height(5.dp)
                            .clip(RoundedCornerShape(50)),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
                if (days > 0) {
                    Text(
                        "已坚持 $days 天",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            IconButton(onClick = { onEdit(task) }) {
                Icon(Icons.Default.Edit, contentDescription = "修改", tint = MaterialTheme.colorScheme.outline)
            }
            if (activeToday) {
                if (done) {
                    Text(
                        "已完成",
                        color = DoneGreen,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                } else {
                    Button(onClick = { onStart(task) }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text(if (task.isPartial) "继续" else "开始")
                    }
                }
            }
        }
    }
}
