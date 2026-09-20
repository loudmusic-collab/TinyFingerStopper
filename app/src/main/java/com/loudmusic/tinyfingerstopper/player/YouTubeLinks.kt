package com.loudmusic.tinyfingerstopper.player

/** Pulls a video id out of whatever a share sheet or a paste happens to hand us. */
object YouTubeLinks {

    private const val ID = "[A-Za-z0-9_-]{11}"

    private val BARE = Regex("^" + ID + "$")

    private val PATTERNS = listOf(
        Regex("youtu\\.be/(" + ID + ")"),
        Regex("[?&]v=(" + ID + ")"),
        Regex("youtube\\.com/(?:embed|shorts|live|v)/(" + ID + ")"),
    )

    fun extractVideoId(input: String?): String? {
        val text = input?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (BARE.matches(text)) return text
        for (pattern in PATTERNS) {
            pattern.find(text)?.let { return it.groupValues[1] }
        }
        return null
    }
}
