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

    // ------------------------------------------------------------ 计划（三级：置顶 + 拖动排序）

    private fun nextOrder(orders: Iterable<Long>): Long =
        maxOf(System.currentTimeMillis(), (orders.maxOrNull() ?: 0L) + 1L)

    /** 修改某个文件夹的内容，同时把它标记为「最近修改」。 */
    private fun updateFolder(folderId: String, change: (PlanFolder) -> PlanFolder) {
        update { s ->
            val order = nextOrder(s.folders.map { it.order })
            s.copy(folders = s.folders.map { folder ->
                if (folder.id != folderId) folder else change(folder).copy(order = order)
            })
        }
    }

    /** 修改某个文件夹的内容，但保留原有排序权重（用于置顶/拖动排序）。 */
    private fun updateFolderKeepOrder(folderId: String, change: (PlanFolder) -> PlanFolder) {
        update { s ->
            s.copy(folders = s.folders.map { folder ->
                if (folder.id != folderId) folder else change(folder)
            })
        }
    }

    fun addFolder(name: String, colorIndex: Int) {
        if (name.isBlank()) return
        update { s ->
            val folder = PlanFolder(
                name = name.trim(),
                colorIndex = colorIndex,
                order = nextOrder(s.folders.map { it.order })
            )
            s.copy(folders = s.folders + folder)
        }
    }

    /** 修改一级：文件夹名称 + 配色 + 可选置顶状态。 */
    fun editFolder(folderId: String, name: String, colorIndex: Int, pinned: Boolean? = null) {
        if (name.isBlank()) return
        updateFolder(folderId) { folder ->
            folder.copy(
                name = name.trim(),
                colorIndex = colorIndex,
                pinned = pinned ?: folder.pinned
            )
        }
    }

    /** 切换一级文件夹置顶状态；置顶时自动提升权重到当前最前。 */
    fun togglePinFolder(folderId: String) {
        update { s ->
            val topOrder = nextOrder(s.folders.map { it.order })
            s.copy(folders = s.folders.map { folder ->
                if (folder.id != folderId) folder
                else {
                    val nextPinned = !folder.pinned
                    folder.copy(
                        pinned = nextPinned,
                        order = if (nextPinned) topOrder else folder.order
                    )
                }
            })
        }
    }

    fun deleteFolder(folderId: String) {
        update { s -> s.copy(folders = s.folders.filterNot { it.id == folderId }) }
    }

    fun addPlan(folderId: String, title: String) {
        if (title.isBlank()) return
        updateFolder(folderId) { f ->
            val nextSort = nextOrder(f.plans.map { it.sortOrder })
            f.copy(plans = f.plans + Plan(title = title.trim(), sortOrder = nextSort))
        }
    }

    /** 修改二级：计划名称 + 可选置顶状态。 */
    fun renamePlan(folderId: String, planId: String, title: String, pinned: Boolean? = null) {
        if (title.isBlank()) return
        updateFolder(folderId) { f ->
            f.copy(plans = f.plans.map { p ->
                if (p.id != planId) p
                else p.copy(title = title.trim(), pinned = pinned ?: p.pinned)
            })
        }
    }

    /** 切换二级计划置顶状态；置顶时自动排到该文件夹最前。 */
    fun togglePinPlan(folderId: String, planId: String) {
        updateFolderKeepOrder(folderId) { f ->
            val topOrder = nextOrder(f.plans.map { it.sortOrder })
            f.copy(plans = f.plans.map { p ->
                if (p.id != planId) p
                else {
                    val nextPinned = !p.pinned
                    p.copy(
                        pinned = nextPinned,
                        sortOrder = if (nextPinned) topOrder else p.sortOrder
                    )
                }
            })
        }
    }

    fun deletePlan(folderId: String, planId: String) {
        updateFolder(folderId) { f -> f.copy(plans = f.plans.filterNot { p -> p.id == planId }) }
    }

    fun addSubPlan(folderId: String, planId: String, title: String, month: Int?, day: Int?) {
        if (title.isBlank()) return
        updateFolder(folderId) { f ->
            f.copy(plans = f.plans.map { p ->
                if (p.id != planId) p
                else {
                    val nextSort = nextOrder(p.subPlans.map { it.sortOrder })
                    val sub = SubPlan(
                        title = title.trim(),
                        dueMonth = month,
                        dueDay = day,
                        sortOrder = nextSort
                    )
                    p.copy(subPlans = p.subPlans + sub)
                }
            })
        }
    }

    /** 修改三级：子计划内容 + 完成日期（月/日可留空）+ 可选置顶状态。 */
    fun editSubPlan(
        folderId: String,
        planId: String,
        subPlanId: String,
        title: String,
        month: Int?,
        day: Int?,
        pinned: Boolean? = null
    ) {
        if (title.isBlank()) return
        updateFolder(folderId) { f ->
            f.copy(plans = f.plans.map { p ->
                if (p.id != planId) p else p.copy(subPlans = p.subPlans.map { sub ->
                    if (sub.id != subPlanId) sub
                    else sub.copy(
                        title = title.trim(),
                        dueMonth = month,
                        dueDay = day,
                        pinned = pinned ?: sub.pinned
                    )
                })
            })
        }
    }

    /** 切换三级子计划置顶状态；置顶时自动排到该计划最前。 */
    fun togglePinSubPlan(folderId: String, planId: String, subPlanId: String) {
        updateFolderKeepOrder(folderId) { f ->
            f.copy(plans = f.plans.map { p ->
                if (p.id != planId) p
                else {
                    val topOrder = nextOrder(p.subPlans.map { it.sortOrder })
                    p.copy(subPlans = p.subPlans.map { sub ->
                        if (sub.id != subPlanId) sub
                        else {
                            val nextPinned = !sub.pinned
                            sub.copy(
                                pinned = nextPinned,
                                sortOrder = if (nextPinned) topOrder else sub.sortOrder
                            )
                        }
                    })
                }
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
                if (p.id ==
