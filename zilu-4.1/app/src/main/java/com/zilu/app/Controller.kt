package com.zilu.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate

/** 新建/编辑任务对话框中收集到的内容。 */
data class TaskDraft(
    val title: String,
    val minutes: Int,
    val reminder: String?,
    val repeat: TaskRepeat,
    val weekDays: List<Int>,
    val date: LocalDate
)

/**
 * 唯一的状态入口。
 * 每次修改都从磁盘重新读取最新状态再合并，避免把后台通知/服务写入的数据覆盖掉。
 */
class ZiluController(private val context: Context) {
    private val repo = ZiluRepository(context)

    var state by mutableStateOf(repo.load())
        private set

    fun refresh() {
        val latest = repo.load()
        if (latest != state) state = latest
    }

    private fun update(change: (AppState) -> AppState) {
        val next = change(repo.load())
        repo.save(next)
        state = next
    }

    // ------------------------------------------------------------------ 任务

    fun saveTask(existing: DailyTask?, draft: TaskDraft) {
        val totalSeconds = (draft.minutes.coerceIn(1, 600) * 60)
        val weekDays = if (draft.repeat == TaskRepeat.WEEKLY) draft.weekDays.distinct().sorted() else emptyList()
        val created = when {
            draft.repeat == TaskRepeat.ONCE -> draft.date.toString()
            existing != null -> existing.createdDate
            else -> LocalDate.now().toString()
        }
        val left = if (existing != null && existing.durationSeconds == totalSeconds) {
            existing.remainingSeconds
        } else {
            totalSeconds
        }
        val base = existing ?: DailyTask(
            title = draft.title.trim(),
            durationSeconds = totalSeconds,
            remainingSeconds = totalSeconds
        )
        val task = base.copy(
            title = draft.title.trim(),
            durationSeconds = totalSeconds,
            remainingSeconds = left.coerceIn(1, totalSeconds),
            reminderTime = draft.reminder,
            repeat = draft.repeat,
            weekDays = weekDays,
            createdDate = created,
            sortOrder = existing?.sortOrder ?: state.tasks.size
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
        update { s -> s.copy(activeFocus = session) }
    }

    /**
     * 中途退出专注：已专注的部分扣掉，剩下的秒数写回任务。
     * 例如计划 1 分钟、专注 30 秒后退出，任务就只剩 30 秒，下次从 30 秒继续。
     */
    fun exitFocus() {
        val focus = state.activeFocus ?: return
        val leftSeconds = ((focus.endAtMillis - System.currentTimeMillis()) / 1000L).toInt()
        TaskTimerService.stop(context)
        if (leftSeconds <= 0) {
            FocusFinisher.finish(context, focus.taskId)
            refresh()
            return
        }
        update { s ->
            s.copy(
                activeFocus = null,
                tasks = s.tasks.map {
                    if (it.id == focus.taskId) {
                        it.copy(remainingSeconds = leftSeconds.coerceIn(1, it.durationSeconds))
                    } else it
                }
            )
        }
    }

    /** 通知栏上的「退出专注」也会走这里，所以前台每秒兜底检查一次。 */
    fun syncFocusState() {
        val focus = state.activeFocus ?: return
        if (System.currentTimeMillis() >= focus.endAtMillis + 1200L) {
            FocusFinisher.finish(context, focus.taskId)
            refresh()
        }
    }

    // ------------------------------------------------------------ 计划（三级）

    /**
     * 新权重永远比现有最大的还大，保证「新建 / 刚修改过的文件夹」一定排在最上方，
     * 即使之前用户手动拖动过顺序也不会失效。
     */
    private fun nextOrder(folders: List<PlanFolder>): Long =
        maxOf(System.currentTimeMillis(), (folders.maxOfOrNull { it.order } ?: 0L) + 1L)

    /** 修改某个文件夹的内容，同时把它标记为「最近修改」。 */
    private fun updateFolder(folderId: String, change: (PlanFolder) -> PlanFolder) {
        update { s ->
            val order = nextOrder(s.folders)
            s.copy(folders = s.folders.map { folder ->
                if (folder.id != folderId) folder else change(folder).copy(order = order)
            })
        }
    }

    fun addFolder(name: String, colorIndex: Int) {
        if (name.isBlank()) return
        update { s ->
            val folder = PlanFolder(name = name.trim(), colorIndex = colorIndex, order = nextOrder(s.folders))
            s.copy(folders = s.folders + folder)
        }
    }

    /** 修改一级：文件夹名称 + 配色。 */
    fun editFolder(folderId: String, name: String, colorIndex: Int) {
        if (name.isBlank()) return
        updateFolder(folderId) { it.copy(name = name.trim(), colorIndex = colorIndex) }
    }

    fun deleteFolder(folderId: String) {
        update { s -> s.copy(folders = s.folders.filterNot { it.id == folderId }) }
    }

    fun addPlan(folderId: String, title: String) {
        if (title.isBlank()) return
        updateFolder(folderId) { f -> f.copy(plans = f.plans + Plan(title = title.trim())) }
    }

    /** 修改二级：计划名称。 */
    fun renamePlan(folderId: String, planId: String, title: String) {
        if (title.isBlank()) return
        updateFolder(folderId) { f ->
            f.copy(plans = f.plans.map { p ->
                if (p.id == planId) p.copy(title = title.trim()) else p
            })
        }
    }

    fun deletePlan(folderId: String, planId: String) {
        updateFolder(folderId) { f -> f.copy(plans = f.plans.filterNot { p -> p.id == planId }) }
    }

    fun addSubPlan(folderId: String, planId: String, title: String, month: Int?, day: Int?) {
        if (title.isBlank()) return
        val sub = SubPlan(title = title.trim(), dueMonth = month, dueDay = day)
        updateFolder(folderId) { f ->
            f.copy(plans = f.plans.map { p ->
                if (p.id == planId) p.copy(subPlans = p.subPlans + sub) else p
            })
        }
    }

    /** 修改三级：子计划内容 + 完成日期（月/日可留空）。 */
    fun editSubPlan(
        folderId: String,
        planId: String,
        subPlanId: String,
        title: String,
        month: Int?,
        day: Int?
    ) {
        if (title.isBlank()) return
        updateFolder(folderId) { f ->
            f.copy(plans = f.plans.map { p ->
                if (p.id != planId) p else p.copy(subPlans = p.subPlans.map { sub ->
                    if (sub.id == subPlanId) sub.copy(title = title.trim(), dueMonth = month, dueDay = day) else sub
                })
            })
        }
    }

    fun toggleSubPlan(folderId: String, planId: String, subPlanId: String) {
        var becameDone = false
        updateFolder(folderId) { f ->
            f.copy(plans = f.plans.map { p ->
                if (p.id != planId) p else p.copy(subPlans = p.subPlans.map { sub ->
                    if (sub.id != subPlanId) sub else {
                        becameDone = !sub.done
                        sub.copy(
                            done = !sub.done,
                            completedAt = if (sub.done) null else LocalDate.now().toString()
                        )
                    }
                })
            })
        }
        if (becameDone) celebrate()
    }

    /** 第二级计划可以独立勾选完成，不需要先把子计划全部勾完。 */
    fun togglePlan(folderId: String, planId: String) {
        var becameDone = false
        updateFolder(folderId) { f ->
            f.copy(plans = f.plans.map { p ->
                if (p.id != planId) p else {
                    becameDone = !p.done
                    p.copy(
                        done = !p.done,
                        completedAt = if (p.done) null else LocalDate.now().toString()
                    )
                }
            })
        }
        if (becameDone) celebrate()
    }

    fun deleteSubPlan(folderId: String, planId: String, subPlanId: String) {
        updateFolder(folderId) { f ->
            f.copy(plans = f.plans.map { p ->
                if (p.id == planId) p.copy(subPlans = p.subPlans.filterNot { it.id == subPlanId }) else p
            })
        }
    }

    fun saveReflection(folderId: String, planId: String, value: String) {
        updateFolder(folderId) { f ->
            f.copy(plans = f.plans.map { p ->
                if (p.id == planId) p.copy(reflection = value) else p
            })
        }
    }

    /** 用户拖动文件夹后的新顺序：按显示顺序重新分配权重，越靠前权重越大。 */
    fun reorderFolders(orderedIds: List<String>) {
        update { s ->
            val top = nextOrder(s.folders) + orderedIds.size
            val rank = orderedIds.withIndex().associate { (index, id) -> id to (top - index) }
            s.copy(folders = s.folders.map { f -> f.copy(order = rank[f.id] ?: f.order) })
        }
    }

    // ------------------------------------------------------------------ 待办

    fun addTodo(title: String, date: LocalDate, colorIndex: Int) {
        if (title.isBlank()) return
        update { s ->
            val order = s.todos.count { it.date == date.toString() }
            s.copy(
                todos = s.todos + TodoItem(
                    title = title.trim(),
                    date = date.toString(),
                    colorIndex = colorIndex,
                    sortOrder = order
                )
            )
        }
    }

    fun toggleTodo(id: String) {
        var becameDone = false
        update { s ->
            val target = s.todos.firstOrNull { it.id == id } ?: return@update s
            becameDone = !target.done
            val flipped = s.todos.map { if (it.id == id) it.copy(done = !it.done) else it }
            // 完成的事项自动沉底：重写这一天的 sortOrder，未完成按原相对顺序上移。
            // 排序只认 sortOrder，这样拖动排序和「完成沉底」不会互相打架。
            val day = target.date
            val sorted = flipped.filter { it.date == day }
                .sortedWith(compareBy({ it.done }, { it.sortOrder }, { it.createdAt }))
            val newIndex = sorted.withIndex().associate { it.value.id to it.index }
            s.copy(todos = flipped.map { item ->
                if (item.date == day) item.copy(sortOrder = newIndex[item.id] ?: item.sortOrder) else item
            })
        }
        if (becameDone) celebrate()
    }

    fun deleteTodo(id: String) {
        update { s -> s.copy(todos = s.todos.filterNot { it.id == id }) }
    }

    /** 拖动排序结束后调用：按新的顺序重写这一天的 sortOrder。 */
    fun reorderTodos(date: LocalDate, orderedIds: List<String>) {
        update { s ->
            val indexOf = orderedIds.withIndex().associate { it.value to it.index }
            s.copy(todos = s.todos.map { todo ->
                if (todo.date != date.toString()) todo
                else todo.copy(sortOrder = indexOf[todo.id] ?: todo.sortOrder)
            })
        }
    }

    // ------------------------------------------------------------------ 小记

    fun saveNote(date: LocalDate, content: String) {
        update { s ->
            val others = s.notes.filterNot { it.date == date.toString() }
            if (content.isBlank()) others else others + DailyNote(date.toString(), content)
        }
    }

    // ------------------------------------------------------------------ 设置

    fun saveSettings(settings: AppSettings) {
        val previous = repo.load().settings
        update { it.copy(settings = settings) }
        if (previous.ringtoneUri != settings.ringtoneUri) {
            Notifier.rebuildForRingtone(context)
        } else {
            Notifier.ensureChannels(context, settings)
        }
    }

    /** 勾选完成时的铃声 + 振动反馈。 */
    private fun celebrate() {
        SoundFeedback.celebrate(context, repo.load().settings)
    }
}

/** 某一天的待办：排序只认 sortOrder，完成沉底与拖动排序都由 sortOrder 表达。 */
fun todosOf(state: AppState, date: LocalDate): List<TodoItem> =
    state.todos
        .filter { it.date == date.toString() }
        .sortedWith(compareBy({ it.sortOrder }, { it.createdAt }))
