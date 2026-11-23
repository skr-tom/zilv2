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

    fun addFolder(name: String, colorIndex: Int) {
        if (name.isBlank()) return
        update { it.copy(folders = it.folders + PlanFolder(name = name.trim(), colorIndex = colorIndex)) }
    }

    fun renameFolder(folderId: String, name: String) {
        if (name.isBlank()) return
        update { s ->
            s.copy(folders = s.folders.map { if (it.id == folderId) it.copy(name = name.trim()) else it })
        }
    }

    fun deleteFolder(folderId: String) {
        update { s -> s.copy(folders = s.folders.filterNot { it.id == folderId }) }
    }

    fun addPlan(folderId: String, title: String) {
        if (title.isBlank()) return
        update { s ->
            s.copy(folders = s.folders.map {
                if (it.id == folderId) it.copy(plans = it.plans + Plan(title = title.trim())) else it
            })
        }
    }

    fun deletePlan(folderId: String, planId: String) {
        update { s ->
            s.copy(folders = s.folders.map {
                if (it.id == folderId) it.copy(plans = it.plans.filterNot { p -> p.id == planId }) else it
            })
        }
    }

    fun addSubPlan(folderId: String, planId: String, title: String, month: Int?, day: Int?) {
        if (title.isBlank()) return
        val sub = SubPlan(title = title.trim(), dueMonth = month, dueDay = day)
        update { s ->
            s.copy(folders = s.folders.map { f ->
                if (f.id != folderId) f else f.copy(plans = f.plans.map { p ->
                    if (p.id != planId) p else p.copy(subPlans = p.subPlans + sub)
                })
            })
        }
    }

    fun toggleSubPlan(folderId: String, planId: String, subPlanId: String) {
        update { s ->
            s.copy(folders = s.folders.map { f ->
                if (f.id != folderId) f else f.copy(plans = f.plans.map { p ->
                    if (p.id != planId) p else p.copy(subPlans = p.subPlans.map { sub ->
                        if (sub.id != subPlanId) sub else sub.copy(
                            done = !sub.done,
                            completedAt = if (sub.done) null else LocalDate.now().toString()
                        )
                    })
                })
            })
        }
    }

    fun deleteSubPlan(folderId: String, planId: String, subPlanId: String) {
        update { s ->
            s.copy(folders = s.folders.map { f ->
                if (f.id != folderId) f else f.copy(plans = f.plans.map { p ->
                    if (p.id != planId) p else p.copy(subPlans = p.subPlans.filterNot { it.id == subPlanId })
                })
            })
        }
    }

    fun saveReflection(folderId: String, planId: String, value: String) {
        update { s ->
            s.copy(folders = s.folders.map { f ->
                if (f.id != folderId) f else f.copy(plans = f.plans.map { p ->
                    if (p.id != planId) p else p.copy(reflection = value)
                })
            })
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
        update { s -> s.copy(todos = s.todos.map { if (it.id == id) it.copy(done = !it.done) else it }) }
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
            val nextNotes = if (content.isBlank()) others else others + DailyNote(date.toString(), content)
            s.copy(notes = nextNotes)
        }
    }

    // ------------------------------------------------------------------ 设置

    fun saveSettings(settings: AppSettings) {
        update { it.copy(settings = settings) }
        Notifier.ensureChannels(context)
    }
}

/** 某一天的待办，未完成的在前（按手动顺序），已完成的自动沉到底部。 */
fun todosOf(state: AppState, date: LocalDate): List<TodoItem> =
    state.todos
        .filter { it.date == date.toString() }
        .sortedWith(compareBy({ it.done }, { it.sortOrder }, { it.createdAt }))
