package chimahon.dictionary

fun hasNewerRevision(current: String?, latest: String?): Boolean {
    if (current == null || latest == null) return false
    if (current == latest) return false

    val currentParts = current.split(".")
    val latestParts = latest.split(".")

    for (i in 0 until maxOf(currentParts.size, latestParts.size)) {
        val c = currentParts.getOrNull(i)
        val l = latestParts.getOrNull(i)
        when {
            c == null && l != null -> return true
            c != null && l == null -> return false
            c != null && l != null -> {
                val ci = c.toIntOrNull()
                val li = l.toIntOrNull()
                when {
                    ci != null && li != null -> {
                        if (ci != li) return ci < li
                    }
                    else -> {
                        if (c != l) return c < l
                    }
                }
            }
        }
    }
    return false
}
