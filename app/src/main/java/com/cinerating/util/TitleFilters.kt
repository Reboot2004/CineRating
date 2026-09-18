package com.cinerating.util

/**
 * Shared text rules for title detection — used by the accessibility-tree path
 * AND the OCR path so both agree on what counts as a movie title.
 * Pure functions: covered by TitleFiltersTest.
 */
object TitleFilters {

    private val BLACKLIST = listOf(
        "Home", "Search", "Settings", "Movies", "TV", "Sports", "Watchlist", "FREE",
        "New Episode", "Premium", "Hotstar", "Netflix", "Disney+", "Watch", "Play", "Menu",
        "Episodes", "More", "Info", "Language", "Subtitles", "Next", "Back", "Skip", "Intro",
        "Details", "Resume", "Download", "Share", "Rate", "More Like This", "Trailers",
        "My Space", "Browse", "Categories", "Coming Soon", "Hubs", "Continue Watching",
        "My Downloads", "watchlist", "login", "kids", "profile", "settings",
        "Trending Now", "Top 10", "Top10", "Popular", "Recommended", "Continue watching",
        "New Releases", "Originals", "My List", "TV Shows", "Shows", "Series",
        "Apps", "App", "YouTube", "Play Store", "Google Play", "Inputs", "Network",
        "Display", "Sound", "Notifications", "Library", "Queue", "Up Next", "Featured",
        "Sign in", "Sign In", "Profiles", "Who's watching", "Manage profiles",
        "Play Next", "Trailer", "Episodes & More", "Remind Me", "HD", "4K", "U/A",
        // Observed on Hotstar TV: genre rails / promo tiles rated as titles
        // ("Action" -> 4.8, "Horror"/"Super Heroes" -> 7.2 badges on headers).
        // "War" deliberately NOT listed: real 2019 film with that exact name.
        "Connect Phone", "Action", "Horror", "Comedy", "Drama", "Thriller",
        "Romance", "Crime", "Mystery", "Fantasy", "Sci-Fi", "Documentary",
        "Animation", "Adventure", "Family", "History", "Music", "Western",
        "Super Heroes", "Superheroes"
    )

    // Row headers contain these ("Continue Watching for X", "New on ...").
    // Plus promo/chrome phrases observed on TV ("Connect Phone" tile,
    // "• New Episode" badges, "Super Heroes" rail).
    private val HEADER_PATTERNS = listOf(
        "continue watch", "new on ", "trending", "top 10", "my list",
        "popular on", "popular ", "because you", "watch again",
        "recently added", "recommended", "originals", "coming soon",
        "my space", "watchlist", "for you", "charts", "critically",
        "blockbuster", "exclusive", "premiere", "live tv", "only on ",
        "new releases", "worth the wait", "favourites", "favorites",
        "connect phone", "connect your", "link your", "scan the qr",
        "scan to ", "new episode", "super hero"
    )

    // Player/metadata chrome, not titles ("2h 46m", "U/A 16+", "7 Languages").
    private val META_PATTERNS = listOf(
        "u/a", "language", "new release", "watch now", "released",
        " mins", " min", "audio", "dolby", " hdr", "channels"
    )

    private val META_REGEXES = listOf(
        Regex("\\d+\\s*h(\\s*\\d+\\s*m)?"), // 2h, 2h 46m
        Regex("\\b\\d+\\s*seasons?\\b"), // 2 Seasons
        Regex("\\bs\\d+\\s*e\\d+\\b"), // S1 E1
        Regex("\\bimdb\\b"), // rating badge text ("IMDb 7.5") being re-rated
        Regex("\\d+(\\.\\d+)?\\s*/\\s*10"), // already-scored text ("7.8/10")
        Regex("^#\\d+") // chart labels ("#2 in English Today")
    )

    fun isLikelyMovieTitle(text: String): Boolean {
        val isBlacklisted = BLACKLIST.any { it.equals(text, ignoreCase = true) }
        val lower = text.lowercase()
        val isHeaderPattern = HEADER_PATTERNS.any { lower.contains(it) }
        val isMetaPattern = META_PATTERNS.any { lower.contains(it) } ||
                META_REGEXES.any { lower.contains(it) }
        val isJustNumbersOrSymbols = text.matches(Regex("^[0-9\\s·:!\\-_\\|]+$"))
        val isTooLong = text.length > 50

        return !isBlacklisted && !isHeaderPattern && !isMetaPattern &&
                !isJustNumbersOrSymbols && !isTooLong && text.length > 2
    }

    fun cleanTitle(text: String?): String? {
        if (text == null) return null
        return text.replace(Regex("^[•·●▪►▸*\\-–—\\s]+"), "")
            .replace(",Movie", "", ignoreCase = true)
            .replace(",Show", "", ignoreCase = true)
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("Season \\d+"), "")
            .trim()
    }

    /** Normalized cache key shared by tree + OCR candidates. */
    fun keyOf(title: String): String = title.lowercase()
}
