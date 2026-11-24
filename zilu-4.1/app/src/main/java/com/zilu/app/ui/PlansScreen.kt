package com.zilu.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.zilu.app.AppState
import com.zilu.app.Plan
import com.zilu.app.PlanFolder
import com.zilu.app.SubPlan
import kotlin.math.roundToInt

private enum class EditKind { FOLDER, PLAN, SUB }

/** 三级里任意一层的编辑目标，统一用一个对话框承载。 */
private data class EditTarget(
    val kind: EditKind,
    val folderId: String,
    val planId: String = "",
    val subId: String = "",
    val title: String = "",
    val colorIndex: Int = 0,
    val month: Int? = null,
    val day: Int? = null
)

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
    onSaveReflection: (String, String, String) -> Unit,
    onEditFolder: (String, String, Int) -> Unit,
    onEditPlan: (String, String, String) -> Unit,
    onEditSub: (String, String, String, String, Int?, Int?) -> Unit,
    onReorderFolders: (List<String>) -> Unit
) {
    // 默认按「最后修改时间」倒序：刚改过的文件夹自动排到最上面
    val ordered = remember(state.folders) { state.folders.sortedByDescending { it.order } }
    var editTarget by remember { mutableStateOf<EditTarget?>(null) }

    // 文件夹拖动：拖动过程中列表顺序保持不变，只让被拖的卡片浮起跟手，松手才一次性提交顺序。
    // 这样不会像「边拖边重排」那样打断手势、让相邻卡片叠在一起。
    var dragId by remember { mutableStateOf<String?>(null) }
    var fromIndex by remember { mutableIntStateOf(-1) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val stepPx = with(LocalDensity.current) { 92.dp.toPx() }
    val latestReorder by rememberUpdatedState(onReorderFolders)
    val latestOrdered by rememberUpdatedState(ordered)

    val targetIndex = if (dragId == null || fromIndex < 0) -1
    else (fromIndex + (dragY / stepPx).roundToInt())
        .coerceIn(0, ordered.lastIndex.coerceAtLeast(0))

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column {
            Text("长期计划", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "文件夹 → 计划 → 子计划；三层都能改名，只有子计划可以设置日期",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (ordered.isEmpty()) {
            EmptyHint("还没有文件夹，点右下角 + 新建一个")
        }

        ordered.forEachIndexed { index, folder ->
            Column {
                // 拖动时的插入位置指示线
                if (dragId != null && targetIndex == index && targetIndex != fromIndex) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                val isDragging = dragId == folder.id
                Box(
                    Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragging) 1f else 0f)
                        .offset { IntOffset(0, if (isDragging) dragY.roundToInt() else 0) }
                ) {
                    FolderCard(
                        folder = folder,
                        elevated = isDragging,
                        dragHandle = Modifier.pointerInput(folder.id, index) {
                            detectDragGestures(
                                onDragStart = {
                                    dragId = folder.id
                                    // 用实时索引，避免顺序变化后闭包里的旧 index 导致排错位
                                    fromIndex = latestOrdered.indexOfFirst { it.id == folder.id }
                                    dragY = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragY += amount.y
                                },
                                onDragEnd = {
                                    val ids = latestOrdered.map { it.id }.toMutableList()
                                    val from = fromIndex
                                    val to = (from + (dragY / stepPx).roundToInt())
                                        .coerceIn(0, ids.lastIndex.coerceAtLeast(0))
                                    if (from in ids.indices && to in ids.indices && from != to) {
                                        val moved = ids.removeAt(from)
                                        ids.add(to, moved)
                                        latestReorder(ids)
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
                        },
                        onAddPlan = onAddPlan,
                        onAddSubPlan = onAddSubPlan,
                        onToggleSub = onToggleSub,
                        onTogglePlan = onTogglePlan,
                        onDeleteSub = onDeleteSub,
                        onDeletePlan = onDeletePlan,
                        onDeleteFolder = onDeleteFolder,
                        onSaveReflection = onSaveReflection,
                        onEdit = {
                            editTarget = EditTarget(
                                kind = EditKind.FOLDER,
                                folderId = folder.id,
                                title = folder.name,
                                colorIndex = folder.colorIndex
                            )
                        },
                        onEditPlan = { plan ->
                            editTarget = EditTarget(
                                kind = EditKind.PLAN,
                                folderId = folder.id,
                                planId = plan.id,
                                title = plan.title
                            )
                        },
                        onEditSub = { plan, sub ->
                            editTarget = EditTarget(
                                kind = EditKind.SUB,
                                folderId = folder.id,
                                planId = plan.id,
                                subId = sub.id,
                                title = sub.title,
                                month = sub.dueMonth,
                                day = sub.dueDay
                            )
                        }
                    )
                }
            }
        }

        if (ordered.size > 1) {
            Text(
                "按住文件夹左侧的把手可以上下拖动排序；平时默认按最近修改时间排列，最新改过的在最上面",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }

    editTarget?.let { target ->
        EditDialog(
            target = target,
            onDismiss = { editTarget = null },
            onConfirm = { title, color, month, day ->
                when (target.kind) {
                    EditKind.FOLDER -> onEditFolder(target.folderId, title, color)
                    EditKind.PLAN -> onEditPlan(target.folderId, target.planId, title)
                    EditKind.SUB -> onEditSub(
                        target.folderId, target.planId, target.subId, title, month, day
                    )
                }
                editTarget = null
            }
        )
    }
}

/** 三级通用的编辑对话框：文件夹可改名称+颜色，计划改名称，子计划改名称+月/日。 */
@Composable
private fun EditDialog(
    target: EditTarget,
    onDismiss: () -> Unit,
    onConfirm: (String, Int, Int?, Int?) -> Unit
) {
    var title by remember(target) { mutableStateOf(target.title) }
    var color by remember(target) { mutableIntStateOf(target.colorIndex) }
    var month by remember(target) { mutableStateOf(target.month?.toString().orEmpty()) }
    var day by remember(target) { mutableStateOf(target.day?.toString().orEmpty()) }

    val monthValue = month.toIntOrNull()
    val dayValue = day.toIntOrNull()
    val bothEmpty = month.isBlank() && day.isBlank()
    val dueOk = target.kind != EditKind.SUB ||
        bothEmpty ||
        ((monthValue ?: 0) in 1..12 && (dayValue ?: 0) in 1..31)
    val valid = title.isNotBlank() && dueOk

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (target.kind) {
                    EditKind.FOLDER -> "修改文件夹"
                    EditKind.PLAN -> "修改计划"
                    EditKind.SUB -> "修改子计划"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = {
                        Text(
                            when (target.kind) {
                                EditKind.FOLDER -> "文件夹名称"
                                EditKind.PLAN -> "计划名称"
                                EditKind.SUB -> "子计划内容"
                            }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (target.kind == EditKind.FOLDER) {
                    Text("文件夹颜色", style = MaterialTheme.typography.labelLarge)
                    ColorPicker(selected = color, palette = FolderPalette, onSelect = { color = it })
                }
                if (target.kind == EditKind.SUB) {
                    Text("完成日期（可以不填）", style = MaterialTheme.typography.labelLarge)
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
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onConfirm(
                        title.trim(),
                        color,
                        if (bothEmpty) null else monthValue,
                        if (bothEmpty) null else dayValue
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun FolderCard(
    folder: PlanFolder,
    elevated: Boolean,
    dragHandle: Modifier,
    onAddPlan: (String) -> Unit,
    onAddSubPlan: (String, String) -> Unit,
    onToggleSub: (String, String, String) -> Unit,
    onTogglePlan: (String, String) -> Unit,
    onDeleteSub: (String, String, String) -> Unit,
    onDeletePlan: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onSaveReflection: (String, String, String) -> Unit,
    onEdit: () -> Unit,
    onEditPlan: (Plan) -> Unit,
    onEditSub: (Plan, SubPlan) -> Unit
) {
    var expanded by remember(folder.id) { mutableStateOf(true) }
    var confirmDelete by remember(folder.id) { mutableStateOf(false) }
    val tone = folderTone(folder.colorIndex)

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = if (elevated) 12.dp else 1.dp
        ),
        colors = CardDefaults.elevatedCardColors(containerColor = tone.container)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, top = 14.dp, bottom = 10.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 拖动把手：只在这块区域响应拖拽，不会和「点击展开/删除」冲突
                Box(
                    Modifier
                        .size(26.dp)
                        .then(dragHandle),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.DragHandle,
                        contentDescription = "拖动排序",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(tone.accent.copy(alpha = 0.18f))
                        .clickable { expanded = !expanded },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = tone.accent)
                }
                Spacer(Modifier.width(8.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { expanded = !expanded }
                ) {
                    Text(
                        folder.name,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = tone.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${folder.plans.size} 个计划 · 已完成 ${folder.donePlans} 个",
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
                SmallActionIcon(
                    icon = Icons.Default.Edit,
                    description = "修改文件夹",
                    onClick = onEdit
                )
                SmallActionIcon(
                    icon = Icons.Default.Delete,
                    description = if (confirmDelete) "再点一次删除文件夹" else "删除文件夹",
                    tint = if (confirmDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                    onClick = { if (confirmDelete) onDeleteFolder(folder.id) else confirmDelete = true }
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "展开或收起文件夹",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { expanded = !expanded }
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
                            onSaveReflection = { text -> onSaveReflection(folder.id, plan.id, text) },
                            onEdit = { onEditPlan(plan) },
                            onEditSub = { sub -> onEditSub(plan, sub) }
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

/** 固定尺寸的小图标按钮，避免默认 48dp 的 IconButton 把标题行挤爆。 */
@Composable
private fun SmallActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: Color = Color.Unspecified
) {
    // 默认参数里不能调用 MaterialTheme（它是 @Composable 的），所以在函数体里解析
    val iconTint = if (tint == Color.Unspecified) MaterialTheme.colorScheme.outline else tint
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = iconTint, modifier = Modifier.size(17.dp))
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
    onSaveReflection: (String) -> Unit,
    onEdit: () -> Unit,
    onEditSub: (SubPlan) -> Unit
) {
    var open by remember(plan.id) { mutableStateOf(false) }
    var reflectionOpen by remember(plan.id) { mutableStateOf(false) }
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
                Spacer(Modifier.width(8.dp))
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
                Spacer(Modifier.width(8.dp))
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
                SmallActionIcon(Icons.Default.Edit, "修改计划", onEdit)
                SmallActionIcon(
                    icon = Icons.Default.Delete,
                    description = if (confirmDelete) "再点一次删除计划" else "删除计划",
                    tint = if (confirmDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                    onClick = { if (confirmDelete) onDeletePlan() else confirmDelete = true }
                )
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
                            onDelete = { onDeleteSub(sub.id) },
                            onEdit = { onEditSub(sub) }
                        )
                    }
                    TextButton(onClick = onAddSubPlan) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("添加子计划")
                    }

                    // 完成心得跟「添加子计划」一样可收缩，默认收起，不占版面
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { reflectionOpen = !reflectionOpen }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (reflection.isBlank()) "完成心得" else "完成心得 · 已填写",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            if (reflectionOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    if (reflectionOpen) {
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
}

@Composable
private fun SubPlanRow(
    sub: SubPlan,
    accent: Color,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit
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
        Spacer(Modifier.width(8.dp))
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
        SmallActionIcon(Icons.Default.Edit, "修改子计划", onEdit)
        SmallActionIcon(Icons.Default.Delete, "删除子计划", onDelete)
    }
}
