package com.cinerating.structure

/**
 * Per-app parsing strategy. The generic tree-walk stays the fallback;
 * apps with known layouts get structural parsing. Keeps app-specific
 * hacks isolated instead of smeared across shared code.
 */
interface AppProfile {
    fun useSegmentation(): Boolean
}

object HotstarProfile : AppProfile {
    override fun useSegmentation(): Boolean = true
}

object DefaultProfile : AppProfile {
    override fun useSegmentation(): Boolean = false
}

fun profileFor(pkg: String): AppProfile {
    val p = pkg.lowercase()
    return if (p.contains("hotstar")) HotstarProfile else DefaultProfile
}
