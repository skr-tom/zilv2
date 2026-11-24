package com.zilu.app

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.util.UUID

/** 应用内更新检查的默认地址；用户可在「设置 → 版本更新」里改成任意静态 JSON 地址。 */
const val DEFAULT_UPDATE_URL = "https://raw.githubusercontent.com/skr-tom/zilv2/main/update.json"

/** 计划/子计划的完成状态。 */
enum class ItemStatus(val label: String) {
    TODO("未开始"),
    IN_PROGRESS("进行中"),
    DONE("已完成");

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.name == value } ?: TODO
    }
}

/** 每日任务的重复方式。 */
enum class TaskRepeat(val label: String) {
    DAILY("每天"),
    WEEKLY("每周"),
    ONCE("不重复");

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.name == value } ?: DAILY
    }
}

/**
 * 每日任务。
 * durationSeconds 是计划总时长，remainingSeconds 是当前剩余时长 —— 中途退出专注时只扣掉已专注的部分，
 * 下次继续时从剩下的时间接着计时（例如计划 1 分钟，专注 30 秒后退出，剩下就是 30 秒）。
 */
data class DailyTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val durationSeconds: Int = 25 * 60,
    val remainingSeconds: Int = durationSeconds,
    /** 截止提醒时间，形如 "20:30"；null 表示不提醒。 */
    val reminderTime: String? = null,
    val createdDate: String = LocalDate.now().toString(),
    val createdAt: String = LocalDateTime.now().toString(),
    val repeat: TaskRepeat = TaskRepeat.DAILY,
    /** ISO 星期，1 = 周一 … 7 = 周日。仅 WEEKLY 使用。 */
    val weekDays: List<Int> = emptyList(),
    val sortOrder: Int = 0
) {
    val activeWeekDays: List<Int>
        get() = weekDays.ifEmpty {
            listOfNotNull(runCatching { LocalDate.parse(createdDate).dayOfWeek.value }.getOrNull())
        }

    /** 计划分钟数（用于展示）。 */
    val planMinutes: Int get() = ((durationSeconds + 59) / 60).coerceAtLeast(1)

    /** 剩余秒数。 */
    val leftSeconds: Int get() = remainingSeconds.coerceAtLeast(0)

    /** 是否正处于「做了一半」的状态。 */
    val isPartial: Boolean get() = leftSeconds in 1 until durationSeconds

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

/**
 * 日历里的一次性待办。colorIndex 决定左侧色条颜色。
 * originalDate 记录这条待办最初是哪一天建的：只要还没完成，每天打开 App 都会自动顺延到今天，
 * 完成后就不再顺延，留在完成当天。
 */
data class TodoItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val date: String,
    val done: Boolean = false,
    val colorIndex: Int = 0,
    val sortOrder: Int = 0,
    val createdAt: String = LocalDateTime.now().toString(),
    /** 顺延前的原始日期；空字符串表示从未顺延过。 */
    val originalDate: String = ""
) {
    /** 是否是从往日顺延过来、且仍未完成。 */
    val rolledOver: Boolean
        get() = !done && originalDate.isNotBlank() && originalDate != date
}

/** 第三级：子计划。只有这一层可以手动填写完成日期（月-日），支持置顶与拖动排序。 */
data class SubPlan
