package com.zilu.app

import java.time.LocalDate
import java.time.LocalDateTime
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
data class SubPlan(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    /** 完成日期，手动输入的月/日；都为 null 表示不设日期。 */
    val dueMonth: Int? = null,
    val dueDay: Int? = null,
    val done: Boolean = false,
    val completedAt: String? = null,
    /** 置顶：排序时优先于 sortOrder。 */
    val pinned: Boolean = false,
    /** 拖动排序权重，越大越靠前；用 Long 便于以时间戳生成。 */
    val sortOrder: Long = 0L
) {
    /** 完成日期的展示文案；未填日期时为 null。 */
    val dueLabel: String?
        get() {
            val m = dueMonth ?: return null
            val d = dueDay ?: return null
            return "${m}月${d}日"
        }

    /** 是否逾期：未完成、填了日期、且该日期早于今天。非法日期（如 2 月 30 日）不算逾期。 */
    val isOverdue: Boolean
        get() {
            if (done) return false
            val m = dueMonth ?: return false
            val d = dueDay ?: return false
            val today = LocalDate.now()
            val due = runCatching { LocalDate.of(today.year, m, d) }.getOrNull() ?: return false
            return today.isAfter(due)
        }

    val status: ItemStatus
        get() = if (done) ItemStatus.DONE else ItemStatus.TODO
}

/**
 * 第二级：计划。本身不设时间，可以独立勾选完成（不依赖子计划），也支持置顶与拖动排序。
 */
data class Plan(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val subPlans: List<SubPlan> = emptyList(),
    val reflection: String = "",
    val createdAt: String = LocalDateTime.now().toString(),
    /** 第二级可独立勾选完成。 */
    val done: Boolean = false,
    val completedAt: String? = null,
    val pinned: Boolean = false,
    val sortOrder: Long = 0L
) {
    val totalSubPlans: Int get() = subPlans.size
    val doneSubPlans: Int get() = subPlans.count { it.done }

    /** 计划自身按子计划统计的进度，仅用于展示；文件夹完成度不采用它。 */
    val progress: Float
        get() = if (subPlans.isEmpty()) 0f else doneSubPlans.toFloat() / subPlans.size

    val status: ItemStatus
        get() = when {
            done -> ItemStatus.DONE
            subPlans.isNotEmpty() && subPlans.all { it.done } -> ItemStatus.DONE
            subPlans.any { it.done } -> ItemStatus.IN_PROGRESS
            else -> ItemStatus.TODO
        }
}

/**
 * 第一级：文件夹。不同文件夹整体配色不同。
 * 完成度只按第二级「计划」计算，不把子计划算进去。
 */
data class PlanFolder(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val colorIndex: Int = 0,
    val plans: List<Plan> = emptyList(),
    val createdAt: String = LocalDateTime.now().toString(),
    /** 修改 / 拖动排序权重，越大越靠前（默认按最近修改时间倒序）。 */
    val order: Long = 0L,
    val pinned: Boolean = false
) {
    val totalPlans: Int get() = plans.size
    /** 完成度只计第二级计划。 */
    val donePlans: Int get() = plans.count { it.done }
    val progress: Float
        get() = if (totalPlans == 0) 0f else donePlans.toFloat() / totalPlans
}

/** 一天的完成记录（专注结束后写入）。 */
data class CompletionLog(
    val id: String = UUID.randomUUID().toString(),
    val taskId: String,
    val taskTitle: String,
    val date: String = LocalDate.now().toString(),
    val completedAt: String = LocalDateTime.now().toString(),
    val minutes: Int = 0
)

/** 每日小记：按日期独立保存。 */
data class DailyNote(
    val date: String,
    val content: String,
    val updatedAt: String = LocalDateTime.now().toString()
)

/** 一次专注会话。用绝对时间戳，进程重启后也能算出剩余时间。 */
data class FocusSession(
    val taskId: String,
    val title: String,
    val startedAtMillis: Long,
    val endAtMillis: Long,
    val durationSeconds: Int,
    val planMinutes: Int
)

/** 预留的账号信息（当前版本未接登录）。 */
data class UserAccount(
    val provider: String = "",
    val displayName: String = ""
)

/** 应用全局状态，持久化在 SharedPreferences("zilu_v4")。 */
data class AppState(
    val tasks: List<DailyTask> = emptyList(),
    val folders: List<PlanFolder> = emptyList(),
    val todos: List<TodoItem> = emptyList(),
    val completionLogs: List<CompletionLog> = emptyList(),
    val notes: List<DailyNote> = emptyList(),
    val activeFocus: FocusSession? = null,
    val account: UserAccount? = null,
    val settings: AppSettings = AppSettings()
)

/** 应用设置。 */
data class AppSettings(
    val darkMode: Boolean = false,
    val fontScale: Float = 1f,
    val themeName: String = "琥珀金",
    val reminderSound: Boolean = true,
    val reminderVibrate: Boolean = true,
    /** 用户在系统铃声选择器里挑的铃声 URI；null = 跟随系统默认。 */
    val ringtoneUri: String? = null,
    /** 完成任务时播放提示音。 */
    val completionSound: Boolean = true,
    /** 完成任务时振动。 */
    val completionVibrate: Boolean = true,
    /** 用户已确认收到测试通知，之后不再显示通知自检面板。 */
    val notificationVerified: Boolean = false,
    /** 启动时自动检查新版本（12 小时一次，静默，没有更新不打扰）。 */
    val autoCheckUpdate: Boolean = true,
    /** 版本清单地址：自建 update.json 或 GitHub Releases latest API。 */
    val updateUrl: String = DEFAULT_UPDATE_URL,
    /** 用户点「跳过此版本」记下的版本号，之后不再提醒这一版。 */
    val skippedVersionCode: Int = 0
)
