package com.zilu.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zilu.app.AppState
import com.zilu.app.Plan
import com.zilu.app.PlanFolder
import com.zilu.app.SubPlan
import kotlin.math.roundToInt

@Composable
fun PlansScreen(
    state: AppState,
    onAddPlan: (String) -> Unit,
    onAddSubPlan: (String, String) -> Unit,
    onToggleSub: (String, String, String) -> Unit,
    onTogglePlan: (String, String) -> Unit,
    onDeleteSub: (String, String, String) -> Unit,
    onDeletePlan: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onSaveReflection: (String, String, String) -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text("长期计划", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "文件夹 → 计划 → 子计划；计划可单独勾选完成，只有子计划可以设置日期",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (state.folders.isEmpty()) {
            item { EmptyHint("还没有文件夹，点右下角 + 新建一个") }
        }

        items(state.folders, key = { it.id }) { folder ->
            FolderCard(
                folder = folder,
                onAddPlan = onAddPlan,
                onAddSubPlan = onAddSubPlan,
                onToggleSub = onToggleSub,
                onTogglePlan = onTogglePlan,
                onDeleteSub = onDeleteSub,
                onDeletePlan = onDeletePlan,
                onDeleteFolder = onDeleteFolder,
                onSaveReflection = onSaveReflection
            )
        }
    }
}

@Composable
private fun FolderCard(
    folder: PlanFolder,
    onAddPlan: (String) -> Unit,
    onAddSubPlan: (String, String) -> Unit,
    onToggleSub: (String, String, String) -> Unit,
    onTogglePlan: (String, String) -> Unit,
    onDeleteSub: (String, String, String) -> Unit,
    onDeletePlan: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onSaveReflection: (String, String, String) -> Unit
) {
    var expanded by remember(folder.id) { mutableStateOf(true) }
    var confirmDelete by remember(folder.id) { mutableStateOf(false) }
    val tone = folderTone(folder.colorIndex)

    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = tone.container)) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(start = 16.dp, top = 14.dp, bottom = 10.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(tone.accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = tone.accent)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        folder.name,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = tone.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${folder.plans.size} 个计划 · 子计划 ${folder.doneSubPlans}/${folder.totalSubPlans}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "${(folder.progress * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = tone.accent
                )
                IconButton(onClick = { if (confirmDelete) onDeleteFolder(folder.id) else confirmDelete = true }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = if (confirmDelete) "再点一次删除文件夹" else "删除文件夹",
                        tint = if (confirmDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            LinearProgressIndicator(
                progress = { folder.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(50)),
                color = tone.accent,
                trackColor = tone.accent.copy(alpha = 0.15f)
            )

            if (expanded) {
                Column(
                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (folder.plans.isEmpty()) {
                        Text(
                            "这个文件夹里还没有计划",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    folder.plans.forEach { plan ->
                        PlanRow(
                            plan = plan,
                            accent = tone.accent,
                            onAddSubPlan = { onAddSubPlan(folder.id, plan.id) },
                            onToggleSub = { subId -> onToggleSub(folder.id, plan.id, subId) },
                            onTogglePlan = { onTogglePlan(folder.id, plan.id) },
                            onDeleteSub = { subId -> onDeleteSub(folder.id, plan.id, subId) },
                            onDeletePlan = { onDeletePlan(folder.id, plan.id) },
                            onSaveReflection = { text -> onSaveReflection(folder.id, plan.id, text) }
                        )
                    }
                    TextButton(onClick = { onAddPlan(folder.id) }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("新建计划")
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanRow(
    plan: Plan,
    accent: Color,
    onAddSubPlan: () -> Unit,
    onToggleSub: (String) -> Unit,
    onTogglePlan: () -> Unit,
    onDeleteSub: (String) -> Unit,
    onDeletePlan: () -> Unit,
    onSaveReflection: (String) -> Unit
) {
    var open by remember(plan.id) { mutableStateOf(false) }
    var confirmDelete by remember(plan.id) { mutableStateOf(false) }
    var reflection by remember(plan.id) { mutableStateOf(plan.reflection) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { open = !open }
                    .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(width = 4.dp, height = 22.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accent)
                )
                Spacer(Modifier.width(10.dp))
                // 第二级计划自己的完成勾选：与子计划互不依赖
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(if (plan.done) DoneGreen else accent.copy(alpha = 0.14f))
                        .clickable { onTogglePlan() },
                    contentAlignment = Alignment.Center
                ) {
                    if (plan.done) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "计划已完成",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        Box(
                            Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(accent.copy(alpha = 0.55f))
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        plan.title,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (plan.done) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                        textDecoration = if (plan.done) TextDecoration.LineThrough else null
                    )
                    Text(
                        when {
                            plan.done -> "已完成"
                            plan.subPlans.isEmpty() -> "暂无子计划"
                            else -> "子计划 ${plan.subPlans.count { it.done }}/${plan.subPlans.size} 已完成"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { if (confirmDelete) onDeletePlan() else confirmDelete = true }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = if (confirmDelete) "再点一次删除计划" else "删除计划",
                        tint = if (confirmDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Icon(
                    if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (open) {
                Column(
                    Modifier.padding(start = 26.dp, end = 12.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    plan.subPlans.forEach { sub ->
                        SubPlanRow(
                            sub = sub,
                            accent = accent,
                            onToggle = { onToggleSub(sub.id) },
                            onDelete = { onDeleteSub(sub.id) }
                        )
                    }
                    TextButton(onClick = onAddSubPlan) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("添加子计划")
                    }
                    OutlinedTextField(
                        value = reflection,
                        onValueChange = { reflection = it },
                        label = { Text("完成心得") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(
                        onClick = { onSaveReflection(reflection) },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("保存心得") }
                }
            }
        }
    }
}

@Composable
private fun SubPlanRow(
    sub: SubPlan,
    accent: Color,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (sub.done) DoneGreen else accent.copy(alpha = 0.14f))
                .clickable { onToggle() },
            contentAlignment = Alignment.Center
        ) {
            if (sub.done) {
                Icon(Icons.Default.Check, contentDescription = "已完成", tint = Color.White, modifier = Modifier.size(16.dp))
            } else {
                Box(Modifier.size(9.dp).clip(CircleShape).background(accent.copy(alpha = 0.55f)))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            sub.title,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (sub.done) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
        )
        sub.dueLabel?.let { label ->
            val overdue = sub.isOverdue
            ToneChip(
                text = if (overdue) "$label 已逾期" else label,
                accent = if (overdue) UndoneRed else accent,
                container = if (overdue) UndoneRed.copy(alpha = 0.12f) else accent.copy(alpha = 0.12f)
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除子计划",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
