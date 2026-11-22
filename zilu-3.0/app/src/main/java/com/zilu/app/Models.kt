package com.zilu.app

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/** Daily tasks are binary: timer success makes them green; all other states remain red. */
enum class ItemStatus(val label: String) {
    TODO("未完成"), IN_PROGRESS("进行中"), DONE("已完成");
    companion object { fun from(value: String?) = entries.firstOrNull { it.name == value } ?: TODO }
}

/** How often a daily task shows up. */
enum class TaskRepeat(val label: String) {
    DAILY("每天"), WEEKLY("每周"), ONCE("不重复");
    companion object { fun from(value: String?) = entries.firstOrNull { it.name == value } ?: DAILY }
}

data class DailyTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val minutes: Int,
    val reminderTime: String? = null,
    val createdDate: String = LocalDate.now().toString(),
    val createdAt: String = LocalDateTime.now().toString(),
    val repeat: TaskRepeat = TaskRepeat.DAILY,
    /** ISO weekdays, 1 = Monday ... 7 = Sunday. Only used when repeat == WEEKLY. */
    val weekDays: List<Int> = emptyList()
) {
    val activeWeekDays: List<Int>
        get() = weekDays.ifEmpty {
            listOfNotNull(runCatching { LocalDate.parse(createdDate).dayOfWeek.value }.getOrNull())
        }

    fun occursOn(day: LocalDate): Boolean {
        val start = runCatching { LocalDate.parse(createdDate) }.getOrNull() ?: return false
        if (day.isBefore(start)) return false
        return when (repeat) {
            TaskRepeat.DAILY -> true
            TaskRepeat.WEEKLY -> day.dayOfWeek.value in activeWeekDays
            TaskRepeat.ONCE -> day == start
        }
    }
}

/** A one-off calendar to-do. Independent from daily tasks and plans. */
data class TodoItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val date: String,
    val done: Boolean = false
)

data class SubGoal(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val status: ItemStatus = ItemStatus.TODO,
    val changedAt: String = LocalDateTime.now().toString()
)

data class Plan(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val dueDate: String,
    val subGoals: List<SubGoal> = listOf(SubGoal(title = "子目标 1")),
    val completionReflection: String = "",
    val createdAt: String = LocalDateTime.now().toString()
) {
    val isOverdue: Boolean get() = runCatching { LocalDate.parse(dueDate).isBefore(LocalDate.now()) }.getOrDefault(false) && calculatedStatus != ItemStatus.DONE
    val calculatedStatus: ItemStatus get() = when {
        subGoals.all { it.status == ItemStatus.DONE } -> ItemStatus.DONE
        subGoals.any { it.status != ItemStatus.TODO } -> ItemStatus.IN_PROGRESS
        else -> ItemStatus.TODO
    }
}

data class CompletionLog(
    val id: String = UUID.randomUUID().toString(),
    val taskId: String,
    val taskTitle: String,
    val date: String = LocalDate.now().toString(),
    val completedAt: String = LocalDateTime.now().toString(),
    val minutes: Int
)

data class DailyNote(val date: String, val content: String = "", val updatedAt: String = LocalDateTime.now().toString())
data class FocusSession(val taskId: String, val title: String, val startedAtMillis: Long, val endAtMillis: Long, val minutes: Int)
data class UserAccount(val provider: String, val displayName: String)
data class AppSettings(val darkMode: Boolean = false, val fontScale: Float = 1f, val themeName: String = "琥珀金")

data class AppState(
    val tasks: List<DailyTask> = emptyList(),
    val plans: List<Plan> = emptyList(),
    val todos: List<TodoItem> = emptyList(),
    val completionLogs: List<CompletionLog> = emptyList(),
    val notes: List<DailyNote> = emptyList(),
    val activeFocus: FocusSession? = null,
    val account: UserAccount? = null,
    val settings: AppSettings = AppSettings()
)
