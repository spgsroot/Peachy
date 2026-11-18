package ru.spgsroot.peachy.data.browser

/** URLs identify subscriptions, never pages visited in the browser. List contents are not redistributed here. */
object FilterCatalog {
    data class ListSpec(val id: String, val title: String, val urls: List<String>, val defaultOn: Boolean)
    private const val EASY = "https://easylist-downloads.adblockplus.org/"
    private const val UBO = "https://ublockorigin.github.io/uAssets/filters/"
    private const val ADGUARD = "https://filters.adtidy.org/extension/ublock/filters/"
    private const val RAW = "https://raw.githubusercontent.com/"
    private fun list(id: String, title: String, url: String, enabled: Boolean = false) = ListSpec(id, title, listOf(url), enabled)
    private fun adguard(id: String, title: String, number: Int, enabled: Boolean = false) = list("adguard-$id", "AdGuard · $title", "$ADGUARD$number.txt", enabled)
    val lists = buildList {
        for (name in listOf("filters", "badware", "privacy", "quick-fixes", "unbreak", "annoyances")) add(list("ubo-$name", "uBlock Origin · $name", "$UBO$name.txt", name != "annoyances"))
        add(list("easylist", "EasyList", "${EASY}easylist.txt", true))
        add(list("easyprivacy", "EasyPrivacy", "${EASY}easyprivacy.txt", true))
        add(list("peter-lowe", "Peter Lowe", "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&mimetype=plaintext", true))
        add(adguard("base", "Base", 2)); add(adguard("tracking", "Tracking protection", 3)); add(adguard("mobile", "Mobile", 11, true))
        add(adguard("annoyances", "Annoyances", 14)); add(adguard("social", "Social", 4)); add(adguard("turkish", "Türkçe", 13, true))
        add(adguard("russian", "Русский", 1, true)); add(adguard("german", "Deutsch", 6)); add(adguard("french", "Français", 16))
        add(adguard("spanish-portuguese", "Español / Português", 9)); add(adguard("japanese", "日本語", 7)); add(adguard("dutch", "Nederlands", 8))
        add(list("easylist-cookie", "EasyList Cookie", "${EASY}fanboy-cookiemonster.txt")); add(list("fanboy-social", "Fanboy Social", "${EASY}fanboy-social.txt")); add(list("fanboy-annoyance", "Fanboy Annoyance", "${EASY}fanboy-annoyance.txt"))
        add(list("ruadlist", "RU AdList", "${EASY}advblock.txt", true)); add(list("easylist-italy", "EasyList Italy", "${EASY}easylistitaly.txt")); add(list("easylist-china", "EasyList China", "${EASY}easylistchina.txt")); add(list("easylist-poland", "EasyList Poland", "${EASY}easylistpolish.txt"))
        add(list("abpindo", "ABPindo", "${RAW}ABPindo/indonesianadblockrules/master/subscriptions/abpindo.txt")); add(list("koreanlist", "YousList", "${RAW}yous/YousList/master/youslist.txt"))
        add(ListSpec("adult", "EasyList Adult", listOf("adult_thirdparty", "adult_specific_block", "adult_specific_hide").map { "${RAW}easylist/easylist/master/easylist_adult/$it.txt" }, true))
    }
    val defaultIds = lists.filter { it.defaultOn }.mapTo(linkedSetOf()) { it.id }
}
