package com.zilu.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 本地离线存储。每次有意义的操作后立即落盘。
 * v4 使用全新的 key（zilu_v4），首次启动会把 v2 的旧数据迁移过来，避免老用户丢数据。
 */
class ZiluRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val legacy = context.getSharedPreferences("zilu_v2", Context.MODE_PRIVATE)

    fun load(): AppState {
        prefs.getString(KEY, null)?.let { raw ->
            return runCatching { decode(JSONObject(raw)) }.getOrElse { AppState() }
        }
        legacy.getString("state", null)?.let { raw ->
            val migrated = runCatching { decodeLegacy(JSONObject(raw)) }.getOrElse { AppState() }
            save(migrated)
            return migrated
        }
        return AppState()
    }

    fun save(state: AppState) {
        prefs.edit().putString(KEY, encode(state).toString()).apply()
    }

    /** 专注计时结束：写入完成记录、把任务时长复原、清空进行中的专注。 */
    fun completeFocus(taskId: String): AppState {
        val current = load()
        val task = current.tasks.firstOrNull { it.id == taskId } ?: return current
        val today = LocalDate.now().toString()
        val already = current.completionLogs.any { it.taskId == taskId && it.date == today }
        val logs = if (already) current.completionLogs
        else current.completionLogs + CompletionLog(taskId = task.id, taskTitle = task.title, minutes = task.planMinutes)
        val tasks = current.tasks.map {
            if (it.id == taskId) it.copy(remainingSeconds = it.durationSeconds) else it
        }
        val next = current.copy(completionLogs = logs, tasks = tasks, activeFocus = null)
        save(next)
        return next
    }

    fun clearActiveFocus() {
        save(load().copy(activeFocus = null))
    }

    // ---------------------------------------------------------------- encode

    private fun encode(s: AppState) = JSONObject().apply {
        put("version", 4)
        put("tasks", JSONArray(s.tasks.map { it.json() }))
        put("folders", JSONArray(s.folders.map { it.json() }))
        put("todos", JSONArray(s.todos.map { it.json() }))
        put("completions", JSONArray(s.completionLogs.map { it.json() }))
        put("notes", JSONArray(s.notes.map { it.json() }))
        put("active", s.activeFocus?.json() ?: JSONObject.NULL)
        put("account", s.account?.json() ?: JSONObject.NULL)
        put("settings", s.settings.json())
    }

    private fun decode(o: JSONObject) = AppState(
        tasks = o.array("tasks") { task(it) },
        folders = o.array("folders") { folder(it) },
        todos = normalizeTodoOrder(o.array("todos") { todo(it) }),
        completionLogs = o.array("completions") { completion(it) },
        notes = o.array("notes") { note(it) },
        activeFocus = o.optJSONObject("active")?.let { focus(it) },
        account = o.optJSONObject("account")?.let { account(it) },
        settings = settings(o.optJSONObject("settings") ?: JSONObject())
    )

    // ---------------------------------------------------------------- legacy

    private fun decodeLegacy(o: JSONObject): AppState {
        val oldTasks = o.array("tasks") { t ->
            val minutes = t.optInt("minutes", 25).coerceIn(1, 600)
            DailyTask(
                id = t.optString("id"),
                title = t.optString("title"),
                durationSeconds = minutes * 60,
                remainingSeconds = minutes * 60,
                reminderTime = t.optString("reminder").ifBlank { null },
                createdDate = t.optString("createdDate", LocalDate.now().toString()),
                createdAt = t.optString("created"),
                repeat = TaskRepeat.from(t.optString("repeat")),
                weekDays = t.optString("weekDays").split(",").mapNotNull { it.trim().toIntOrNull() }
            )
        }
        val oldPlans = o.array("plans") { p ->
            val due = runCatching { LocalDate.parse(p.optString("dueDate")) }.getOrNull()
            Plan(
                id = p.optString("id"),
                title = p.optString("title"),
                subPlans = p.array("goals") { g ->
                    SubPlan(
                        id = g.optString("id"),
                        title = g.optString("title"),
                        dueMonth = due?.monthValue,
                        dueDay = due?.dayOfMonth,
                        done = ItemStatus.from(g.optString("status")) == ItemStatus.DONE
                    )
                },
                reflection = p.optString("reflection"),
                createdAt = p.optString("created")
            )
        }
        val folders = if (oldPlans.isEmpty()) emptyList()
        else listOf(PlanFolder(name = "我的计划", colorIndex = 0, plans = oldPlans))
        return AppState(
            tasks = oldTasks,
            folders = folders,
            todos = normalizeTodoOrder(o.array("todos") { todo(it) }),
            completionLogs = o.array("completions") { completion(it) },
            notes = o.array("notes") { note(it) },
            activeFocus = null,
            settings = settings(o.optJSONObject("settings") ?: JSONObject())
        )
    }

    /**
     * 老版本把「完成沉底」写在排序逻辑里，v4 改成统一用 sortOrder 表达。
     * 读取时按日期规整一次：未完成保持原有相对顺序在前，已完成的沉到底部。
     */
    private fun normalizeTodoOrder(todos: List<TodoItem>): List<TodoItem> =
        todos.groupBy { it.date }.values.flatMap { group ->
            group.sortedWith(compareBy({ it.done }, { it.sortOrder }, { it.createdAt }))
                .mapIndexed { index, item -> item.copy(sortOrder = index) }
        }

    // ---------------------------------------------------------------- helpers

    private fun <T : Any> JSONObject.array(name: String, f: (JSONObject) -> T): List<T> {
        val a = optJSONArray(name) ?: return emptyList()
        return List(a.length()) { i -> runCatching { f(a.getJSONObject(i)) }.getOrNull() }.filterNotNull()
    }

    private fun DailyTask.json() = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("duration", durationSeconds)
        put("remaining", remainingSeconds)
        // 注意：字符串不要写入 JSONObject.NULL，否则 optString 会读回 "null" 字面量
        reminderTime?.let { put("reminder", it) }
        put("createdDate", createdDate)
        put("created", createdAt)
        put("repeat", repeat.name)
        put("weekDays", weekDays.joinToString(","))
        put("sortOrder", sortOrder)
    }

    private fun task(o: JSONObject): DailyTask {
        val duration = o.optInt("duration", o.optInt("minutes", 25) * 60).coerceIn(5, 600 * 60)
        val remaining = o.optInt("remaining", duration).coerceIn(0, duration)
        return DailyTask(
            id = o.optString("id"),
            title = o.optString("title"),
            durationSeconds = duration,
            remainingSeconds = remaining,
            reminderTime = o.optString("reminder").ifBlank { null },
            createdDate = o.optString("createdDate", LocalDate.now().toString()),
            createdAt = o.optString("created"),
            repeat = TaskRepeat.from(o.optString("repeat")),
            weekDays = o.optString("weekDays").split(",").mapNotNull { it.trim().toIntOrNull() },
            sortOrder = o.optInt("sortOrder", 0)
        )
    }

    private fun TodoItem.json() = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("date", date)
        put("done", done)
        put("color", colorIndex)
        put("sortOrder", sortOrder)
        put("created", createdAt)
    }

    private fun todo(o: JSONObject) = TodoItem(
        id = o.optString("id"),
        title = o.optString("title"),
        date = o.optString("date"),
        done = o.optBoolean("done"),
        colorIndex = o.optInt("color", 0),
        sortOrder = o.optInt("sortOrder", 0),
        createdAt = o.optString("created")
    )

    private fun SubPlan.json() = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("dueMonth", dueMonth ?: JSONObject.NULL)
        put("dueDay", dueDay ?: JSONObject.NULL)
        put("done", done)
        completedAt?.let { put("completedAt", it) }
    }

    private fun subPlan(o: JSONObject) = SubPlan(
        id = o.optString("id"),
        title = o.optString("title"),
        dueMonth = if (o.isNull("dueMonth")) null else o.optInt("dueMonth"),
        dueDay = if (o.isNull("dueDay")) null else o.optInt("dueDay"),
        done = o.optBoolean("done"),
        completedAt = o.optString("completedAt").ifBlank { null }
    )

    private fun Plan.json() = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("subPlans", JSONArray(subPlans.map { it.json() }))
        put("reflection", reflection)
        put("created", createdAt)
        put("done", done)
        completedAt?.let { put("completedAt", it) }
    }

    private fun plan(o: JSONObject) = Plan(
        id = o.optString("id"),
        title = o.optString("title"),
        subPlans = o.array("subPlans") { subPlan(it) },
        reflection = o.optString("reflection"),
        createdAt = o.optString("created"),
        done = o.optBoolean("done"),
        completedAt = o.optString("completedAt").ifBlank { null }
    )

    private fun PlanFolder.json() = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("color", colorIndex)
        put("plans", JSONArray(plans.map { it.json() }))
        put("created", createdAt)
    }

    private fun folder(o: JSONObject) = PlanFolder(
        id = o.optString("id"),
        name = o.optString("name"),
        colorIndex = o.optInt("color", 0),
        plans = o.array("plans") { plan(it) },
        createdAt = o.optString("created")
    )

    private fun CompletionLog.json() = JSONObject().apply {
        put("id", id)
        put("taskId", taskId)
        put("title", taskTitle)
        put("date", date)
        put("at", completedAt)
        put("minutes", minutes)
    }

    private fun completion(o: JSONObject) = CompletionLog(
        o.optString("id"), o.optString("taskId"), o.optString("title"),
        o.optString("date"), o.optString("at"), o.optInt("minutes")
    )

    private fun DailyNote.json() = JSONObject().apply {
        put("date", date)
        put("content", content)
        put("updated", updatedAt)
    }

    private fun note(o: JSONObject) = DailyNote(o.optString("date"), o.optString("content"), o.optString("updated"))

    private fun FocusSession.json() = JSONObject().apply {
        put("task", taskId)
        put("title", title)
        put("start", startedAtMillis)
        put("end", endAtMillis)
        put("duration", durationSeconds)
        put("planMinutes", planMinutes)
    }

    private fun focus(o: JSONObject) = FocusSession(
        o.optString("task"), o.optString("title"), o.optLong("start"), o.optLong("end"),
        o.optInt("duration", 1500), o.optInt("planMinutes", 25)
    )

    private fun UserAccount.json() = JSONObject().apply {
        put("provider", provider)
        put("name", displayName)
    }

    private fun account(o: JSONObject) = UserAccount(o.optString("provider"), o.optString("name"))

    private fun AppSettings.json() = JSONObject().apply {
        put("dark", darkMode)
        put("font", fontScale.toDouble())
        put("theme", themeName)
        put("sound", reminderSound)
        put("vibrate", reminderVibrate)
        ringtoneUri?.let { put("ringtone", it) }
        put("completionSound", completionSound)
        put("completionVibrate", completionVibrate)
        put("notificationVerified", notificationVerified)
    }

    private fun settings(o: JSONObject) = AppSettings(
        darkMode = o.optBoolean("dark"),
        fontScale = o.optDouble("font", 1.0).toFloat().coerceIn(0.8f, 1.4f),
        themeName = o.optString("theme", "琥珀金"),
        reminderSound = o.optBoolean("sound", true),
        reminderVibrate = o.optBoolean("vibrate", true),
        ringtoneUri = if (o.isNull("ringtone")) null else o.optString("ringtone").ifBlank { null },
        completionSound = o.optBoolean("completionSound", true),
        completionVibrate = o.optBoolean("completionVibrate", true),
        notificationVerified = o.optBoolean("notificationVerified", false)
    )

    companion object {
        private const val PREFS = "zilu_v4"
        private const val KEY = "state"
    }
}
