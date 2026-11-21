package com.zilu.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Offline-first storage. Every mutation replaces one JSON snapshot synchronously. */
class ZiluRepository(context: Context) {
    private val prefs = context.getSharedPreferences("zilu_v2", Context.MODE_PRIVATE)

    fun load(): AppState = runCatching { decode(JSONObject(prefs.getString("state", "{}")!!)) }.getOrElse { AppState() }
    fun save(state: AppState) { prefs.edit().putString("state", encode(state).toString()).commit() }
    fun export(state: AppState): String = encode(state).toString(2)
    fun restore(json: String): AppState = decode(JSONObject(json)).also(::save)

    private fun encode(state: AppState) = JSONObject().apply {
        put("tasks", JSONArray(state.tasks.map { it.json() }))
        put("plans", JSONArray(state.plans.map { it.json() }))
        put("deletedPlans", JSONArray(state.deletedPlans.map { it.json() }))
        put("logs", JSONArray(state.statusLogs.map { it.json() }))
        put("summaries", JSONArray(state.summaries.map { it.json() }))
        put("focus", JSONArray(state.focusRecords.map { it.json() }))
        put("settings", state.settings.json())
    }

    private fun decode(root: JSONObject) = AppState(
        tasks = root.array("tasks") { task(it) }, plans = root.array("plans") { plan(it) },
     deletedPlans = root.array("deletedPlans") { plan(it) }, statusLogs = root.array("logs") { log(it) },
        summaries = root.array("summaries") { summary(it) }, focusRecords = root.array("focus") { focus(it) },
        settings = settings(root.optJSONObject("settings") ?: JSONObject())
    )

    private fun <T> JSONObject.array(name: String, mapper: (JSONObject) -> T): List<T> =
        (optJSONArray(name) ?: JSONArray()).let { a -> List(a.length()) { mapper(a.getJSONObject(it)) } }

    private fun DailyTask.json() = JSONObject().apply { put("id", id); put("title", title); put("date", date); put("status", status.name); put("repeat", repeat.name); put("reminder", reminderTime); put("created", createdAt); put("changed", statusChangedAt) }
    private fun task(o: JSONObject) = DailyTask(o.optString("id"), o.optString("title"), o.optString("date"), ItemStatus.from(o.optString("status")), RepeatRule.from(o.optString("repeat")), o.optString("reminder").ifBlank { null }, o.optString("created"), o.optString("changed"))
    private fun SubGoal.json() = JSONObject().apply { put("id", id); put("title", title); put("status", status.name); put("changed", changedAt) }
    private fun subGoal(o: JSONObject) = SubGoal(o.optString("id"), o.optString("title"), ItemStatus.from(o.optString("status")), o.optString("changed"))
    private fun Plan.json() = JSONObject().apply { put("id", id); put("title", title); put("dueText", dueText); put("dueDate", dueDate); put("status", status.name); put("goals", JSONArray(subGoals.map { it.json() })); put("reflection", completionReflection); put("reminder", reminderTime); put("created", createdAt); put("changed", statusChangedAt); put("deleted", deletedAt) }
    private fun plan(o: JSONObject) = Plan(o.optString("id"), o.optString("title"), o.optString("dueText", "完成时间待定"), o.optString("dueDate").ifBlank { null }, ItemStatus.from(o.optString("status")), o.array("goals") { subGoal(it) }, o.optString("reflection"), o.optString("reminder").ifBlank { null }, o.optString("created"), o.optString("changed"), o.optString("deleted").ifBlank { null })
    private fun StatusLog.json() = JSONObject().apply { put("id", id); put("type", itemType); put("item", itemId); put("title", title); put("old", oldStatus.name); put("new", newStatus.name); put("at", at) }
    private fun log(o: JSONObject) = StatusLog(o.optString("id"), o.optString("type"), o.optString("item"), o.optString("title"), ItemStatus.from(o.optString("old")), ItemStatus.from(o.optString("new")), o.optString("at"))
    private fun DailySummary.json() = JSONObject().apply { put("date", date); put("completed", completed); put("improvement", improvement); put("tomorrow", tomorrow); put("updated", updatedAt) }
    private fun summary(o: JSONObject) = DailySummary(o.optString("date"), o.optString("completed"), o.optString("improvement"), o.optString("tomorrow"), o.optString("updated"))
    private fun FocusRecord.json() = JSONObject().apply { put("id", id); put("title", title); put("started", startedAt); put("seconds", focusedSeconds); put("completed", completed) }
    private fun focus(o: JSONObject) = FocusRecord(o.optString("id"), o.optString("title"), o.optString("started"), o.optInt("seconds"), o.optBoolean("completed"))
    private fun AppSettings.json() = JSONObject().apply { put("dark", darkMode); put("font", fontScale.toDouble()); put("theme", themeName); put("soundUri", soundUri); put("soundName", soundName) }
    private fun settings(o: JSONObject) = AppSettings(o.optBoolean("dark"), o.optDouble("font", 1.0).toFloat(), o.optString("theme", "琥珀金"), o.optString("soundUri").ifBlank { null }, o.optString("soundName", "传统提示音"))
}
