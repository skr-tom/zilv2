package com.zilu.app

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

enum class ItemStatus(val label: String, val weight: Float) {
    TODO("未完成", 0f), IN_PROGRESS("进行中", .5f), DONE("已完成", 1f);
    companion object { fun from(value: String?) = entries.firstOrNull { it.name == value } ?: TODO }
}

enum class RepeatRule(val label: String) {
    NONE("不重复"), DAILY("每天"), WEEKLY("每周");
    companion object { fun from(value: String?) = entries.firstOrNull { it.name == value } ?: NONE }
}

data class DailyTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val date: String = LocalDate.now().toString(),
    val status: ItemStatus = ItemStatus.TODO,
    val repeat: RepeatRule = RepeatRule.NONE,
    val reminderTime: String? = null,
    val createdAt: String = LocalDateTime.now().toString(),
    val statusChangedAt: String = LocalDateTime.now().toString()
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
    val dueText: String = "完成时间待定",
    val dueDate: String? = null,
    val status: ItemStatus = ItemStatus.TODO,
    val subGoals: List<SubGoal> = emptyList(),
    val completionReflection: String = "",
    val reminderTime: String? = null,
    val createdAt: String = LocalDateTime.now().toString(),
    val statusChangedAt: String = LocalDateTime.now().toString(),
    val deletedAt: String? = null
) {
    val isOverdue: Boolean get() = dueDate?.let { runCatching { LocalDate.parse(it).isBefore(LocalDate.now()) }.getOrDefault(false) } == true && status != ItemStatus.DONE
    val calculatedStatus: ItemStatus get() = if (subGoals.isEmpty()) status else when {
        subGoals.all { it.status == ItemStatus.DONE } -> ItemStatus.DONE
        subGoals.any { it.status != ItemStatus.TODO } -> ItemStatus.IN_PROGRESS
        else -> ItemStatus.TODO
    }
}

data class StatusLog(
    val id: String = UUID.randomUUID().toString(),
    val itemType: String,
    val itemId: String,
    val title: String,
    val oldStatus: ItemStatus,
    val newStatus: ItemStatus,
    val at: String = LocalDateTime.now().toString()
)

data class DailySummary(
    val date: String,
    val completed: String = "",
    val improvement: String = "",
    val tomorrow: String = "",
    val updatedAt: String = LocalDateTime.now().toString()
)

data class FocusRecord(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val startedAt: String,
    val focusedSeconds: Int,
    val completed: Boolean
)

data class AppSettings(
    val darkMode: Boolean = false,
    val fontScale: Float = 1f,
    val themeName: String = "琥珀金",
    val soundUri: String? = null,
    val soundName: String = "传统提示音"
)

data class AppState(
    val tasks: List<DailyTask> = emptyList(),
    val plans: List<Plan> = emptyList(),
    val deletedPlans: List<Plan> = emptyList(),
    val statusLogs: List<StatusLog> = emptyList(),
    val summaries: List<DailySummary> = emptyList(),
    val focusRecords: List<FocusRecord> = emptyList(),
    val settings: AppSettings = AppSettings()
)
