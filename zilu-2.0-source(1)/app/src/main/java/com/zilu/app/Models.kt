package com.zilu.app

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/** Daily tasks are binary: timer success makes them green; all other states remain red. */
enum class ItemStatus(val label: String) {
    TODO("未完成"), IN_PROGRESS("进行中"), DONE("已完成");
    companion object { fun from(value: String?) = entries.firstOrNull { it.name == value } ?: TODO }
}

data class DailyTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val minutes: Int,
    val reminderTime: String? = null,
    val createdDate: String = LocalDate.now().toString(),
    val createdAt: String = LocalDateTime.now().toString()
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
    val completionLogs: List<CompletionLog> = emptyList(),
    val notes: List<DailyNote> = emptyList(),
    val activeFocus: FocusSession? = null,
    val account: UserAccount? = null,
    val settings: AppSettings = AppSettings()
)
