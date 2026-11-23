package com.zilu.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zilu.app.AppState
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 每日小记：按日期独立存储。
 * 顶部可以直接选任意一天（补写前几天的内容），下方「往期小记」也能点开切换。
 */
@Composable
fun NotesScreen(state: AppState, onSave: (LocalDate, String) -> Unit) {
    val today = LocalDate.now()
    var opened by remember { mutableStateOf(today) }
    var showDatePicker by remember { mutableStateOf(false) }

    fun stored(day: LocalDate): String =
        state.notes.firstOrNull { it.date == day.toString() }?.content.orEmpty()

    var text by remember(opened) { mutableStateOf(stored(opened)) }

    // 停止输入 0.6 秒后自动保存到「当前选中的那一天」，不会串到别的日期
    LaunchedEffect(opened, text) {
        if (text != stored(opened)) {
            delay(600)
            if (text != stored(opened)) onSave(opened, text)
        }
    }

    fun openDay(day: LocalDate) {
        if (day == opened) return
        if (text != stored(opened)) onSave(opened, text)
        opened = day
    }

    val history = state.notes
        .filter { it.content.isNotBlank() }
        .sortedByDescending { it.date }

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "每日小记",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (opened != today) {
                TextButton(onClick = { openDay(today) }) { Text("回到今天") }
            }
        }

        // 点这行文字就能选日期
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { showDatePicker = true }
        ) {
            Text(
                if (opened == today) "今天 · " + opened.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA))
                else opened.format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.CHINA)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text("  ▾", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(
                "选择日期",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(4.dp))

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("写点什么？停止输入会自动保存到上面选的日期") },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 8.dp),
            shape = RoundedCornerShape(16.dp)
        )

        Text(
            "往期小记 · 点开可以继续写",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
        )

        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (history.isEmpty()) {
                EmptyHint("还没有记录，写下第一条吧")
            }
            history.forEach { note ->
                val day = runCatching { LocalDate.parse(note.date) }.getOrNull() ?: return@forEach
                val isOpen = day == opened
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isOpen) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openDay(day) }
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                        Text(
                            day.format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.CHINA)),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isOpen) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            note.content.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        var picked by remember(opened) { mutableStateOf(opened) }
        AlertDialog(
            onDismissRequest = { showDatePicker = false },
            title = { Text("选择日期") },
            text = {
                Column {
                    Text(
                        "可以补写前几天的小记，每天的内容各自独立保存、互不影响。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    DateWheel(picked) { picked = it }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = { picked = today }) { Text("回到今天") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    openDay(picked)
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } }
        )
    }
}
