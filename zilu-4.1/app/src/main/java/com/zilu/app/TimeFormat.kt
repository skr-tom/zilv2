package com.zilu.app

/** 把毫秒格式化成时钟：不足 1 小时显示 mm:ss，否则 h:mm:ss。 */
fun formatClock(millis: Long): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) {
        String.format("%d:%02d:%02d", h, m, s)
    } else {
        String.format("%02d:%02d", m, s)
    }
}

/** 把剩余秒数格式化成中文描述，例如「12 分 05 秒」「1 小时 3 分」。 */
fun formatLeft(seconds: Int): String {
    val total = seconds.coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return when {
        h > 0 -> "${h} 小时 ${m} 分"
        m > 0 -> "${m} 分 ${"%02d".format(s)} 秒"
        else -> "${s} 秒"
    }
}
