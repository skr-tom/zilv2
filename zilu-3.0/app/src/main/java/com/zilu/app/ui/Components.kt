package com.zilu.app.ui

import android.widget.NumberPicker
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.time.LocalDate
import java.time.YearMonth

/** 上下滚动的数字轮盘。 */
@Composable
fun NumberWheel(
    value: Int,
    range: IntRange,
    onValue: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier.height(118.dp),
        factory = { context ->
            NumberPicker(context).apply {
                minValue = range.first
                maxValue = range.last
                wrapSelectorWheel = true
                descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
                this.value = value.coerceIn(range.first, range.last)
                setOnValueChangedListener { _, _, newValue -> onValue(newValue) }
            }
        },
        update = { picker ->
            if (picker.minValue != range.first) picker.minValue = range.first
            if (picker.maxValue != range.last) picker.maxValue = range.last
            val safe = value.coerceIn(range.first, range.last)
            if (picker.value != safe) picker.value = safe
        }
    )
}

/** 手动输入的数字框。 */
@Composable
fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    suffix: String,
    modifier: Modifier = Modifier,
    maxLength: Int = 2
) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw -> onValueChange(raw.filter { it.isDigit() }.take(maxLength)) },
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

/** 提醒时间：手动输入「时」「分」。 */
@Composable
fun TimeFields(
    hour: String,
    minute: String,
    onHour: (String) -> Unit,
    onMinute: (String) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        NumberField(hour, onHour, "提醒", "时", Modifier.width(104.dp))
        Text(":", Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.titleMedium)
        NumberField(minute, onMinute, " ", "分", Modifier.width(104.dp))
    }
}

/** 预完成时间：手动输入「月」「日」。 */
@Composable
fun MonthDayFields(
    month: String,
    day: String,
    onMonth: (String) -> Unit,
    onDay: (String) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        NumberField(month, onMonth, "预完成", "月", Modifier.width(104.dp))
        Spacer(Modifier.width(8.dp))
        NumberField(day, onDay, " ", "日", Modifier.width(104.dp))
    }
}

/** 选择一个色块（文件夹配色 / 待办配色）。 */
@Composable
fun ColorPicker(
    selected: Int,
    palette: List<ToneStyle>,
    onSelect: (Int) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        palette.forEachIndexed { index, tone ->
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(tone.accent)
                    .then(
                        if (index == selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        else Modifier
                    )
                    .clickable { onSelect(index) }
            )
        }
    }
}

/** 灰色小标签。 */
@Composable
fun ToneChip(text: String, accent: Color, container: Color) {
    Box(
        Modifier
            .clip(MaterialTheme.shapes.small)
            .background(container)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, color = accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(vertical = 12.dp)
    )
}

/** 年 / 月 / 日 三个轮盘。 */
@Composable
fun DateWheel(value: LocalDate, onValue: (LocalDate) -> Unit) {
    var year by remember(value) { mutableIntStateOf(value.year) }
    var month by remember(value) { mutableIntStateOf(value.monthValue) }
    var day by remember(value) { mutableIntStateOf(value.dayOfMonth) }

    LaunchedEffect(year, month, day) {
        val safeDay = day.coerceAtMost(YearMonth.of(year, month).lengthOfMonth())
        val picked = LocalDate.of(year, month, safeDay)
        if (picked != value) onValue(picked)
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        NumberWheel(year, LocalDate.now().year - 3..LocalDate.now().year + 10, { year = it }, Modifier.weight(1f))
        Text("年")
        NumberWheel(month, 1..12, { month = it }, Modifier.weight(1f))
        Text("月")
        NumberWheel(day, 1..31, { day = it }, Modifier.weight(1f))
        Text("日")
    }
}
