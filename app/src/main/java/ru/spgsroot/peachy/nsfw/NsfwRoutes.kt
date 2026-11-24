package ru.spgsroot.peachy.nsfw

/**
 * Adult-manga destinations, both the graph patterns and the concrete routes stored for restore.
 * Gallery, player, documents and the general browser are not adult destinations. Toy control
 * and page-audio capture are closed next to this check, not by a route name.
 */
object NsfwRoutes {
    fun isNsfw(route: String?): Boolean {
        if (route.isNullOrBlank()) return false
        val head = route.substringBefore('?')
        return head == "manga" || head.startsWith("manga/") || head.startsWith("mangatags/")
    }
}
