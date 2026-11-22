package com.zilu.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Private offline store, persisted after every meaningful action. */
class ZiluRepository(context: Context) {
    private val prefs = context.getSharedPreferences("zilu_v2", Context.MODE_PRIVATE)
    fun load(): AppState = runCatching { decode(JSONObject(prefs.getString("state", "{}")!!)) }.getOrElse { AppState() }
    fun save(state: AppState) { prefs.edit().putString("state", encode(state).toString()).commit() }
    fun completeTask(taskId: String): AppState {
        val current = load(); val task = current.tasks.firstOrNull { it.id == taskId } ?: return current
        if (current.completionLogs.any { it.taskId == taskId && it.date == LocalDate.now().toString() }) return current
        return current.copy(completionLogs = current.completionLogs + CompletionLog(taskId = task.id, taskTitle = task.title, minutes = task.minutes), activeFocus = null).also(::save)
    }
    fun clearActiveFocus() { save(load().copy(activeFocus = null)) }
    private fun encode(s: AppState) = JSONObject().apply {
        put("tasks", JSONArray(s.tasks.map { it.json() })); put("plans", JSONArray(s.plans.map { it.json() }))
        put("completions", JSONArray(s.completionLogs.map { it.json() })); put("notes", JSONArray(s.notes.map { it.json() }))
        put("active", s.activeFocus?.json()); put("account", s.account?.json()); put("settings", s.settings.json())
    }
    private fun decode(o: JSONObject) = AppState(
        tasks = o.array("tasks") { task(it) }, plans = o.array("plans") { plan(it) }, completionLogs = o.array("completions") { completion(it) }, notes = o.array("notes") { note(it) }, activeFocus = o.optJSONObject("active")?.let(::focus), account = o.optJSONObject("account")?.let(::account), settings = settings(o.optJSONObject("settings") ?: JSONObject())
    )
    private fun <T> JSONObject.array(name: String, f: (JSONObject) -> T) = (optJSONArray(name) ?: JSONArray()).let { a -> List(a.length()) { f(a.getJSONObject(it)) } }
    private fun DailyTask.json() = JSONObject().apply { put("id", id); put("title", title); put("minutes", minutes); put("reminder", reminderTime); put("createdDate", createdDate); put("created", createdAt) }
    private fun task(o: JSONObject) = DailyTask(o.optString("id"), o.optString("title"), o.optInt("minutes", 25), o.optString("reminder").ifBlank { null }, o.optString("createdDate", LocalDate.now().toString()), o.optString("created"))
    private fun SubGoal.json() = JSONObject().apply { put("id", id); put("title", title); put("status", status.name); put("changed", changedAt) }
    private fun goal(o: JSONObject) = SubGoal(o.optString("id"), o.optString("title"), ItemStatus.from(o.optString("status")), o.optString("changed"))
    private fun Plan.json() = JSONObject().apply { put("id", id); put("title", title); put("dueDate", dueDate); put("goals", JSONArray(subGoals.map { it.json() })); put("reflection", completionReflection); put("created", createdAt) }
    private fun plan(o: JSONObject) = Plan(o.optString("id"), o.optString("title"), o.optString("dueDate"), o.array("goals") { goal(it) }.ifEmpty { listOf(SubGoal(title = "子目标 1")) }, o.optString("reflection"), o.optString("created"))
    private fun CompletionLog.json() = JSONObject().apply { put("id", id); put("taskId", taskId); put("title", taskTitle); put("date", date); put("at", completedAt); put("minutes", minutes) }
    private fun completion(o: JSONObject) = CompletionLog(o.optString("id"), o.optString("taskId"), o.optString("title"), o.optString("date"), o.optString("at"), o.optInt("minutes"))
    private fun DailyNote.json() = JSONObject().apply { put("date", date); put("content", content); put("updated", updatedAt) }
    private fun note(o: JSONObject) = DailyNote(o.optString("date"), o.optString("content"), o.optString("updated"))
    private fun FocusSession.json() = JSONObject().apply { put("task", taskId); put("title", title); put("start", startedAtMillis); put("end", endAtMillis); put("minutes", minutes) }
    private fun focus(o: JSONObject) = FocusSession(o.optString("task"), o.optString("title"), o.optLong("start"), o.optLong("end"), o.optInt("minutes"))
    private fun UserAccount.json() = JSONObject().apply { put("provider", provider); put("name", displayName) }
    private fun account(o: JSONObject) = UserAccount(o.optString("provider"), o.optString("name"))
    private fun AppSettings.json() = JSONObject().apply { put("dark", darkMode); put("font", fontScale.toDouble()); put("theme", themeName) }
    private fun settings(o: JSONObject) = AppSettings(o.optBoolean("dark"), o.optDouble("font", 1.0).toFloat(), o.optString("theme", "琥珀金"))
}
