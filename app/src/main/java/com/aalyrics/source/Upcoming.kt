package com.aalyrics.source

/** A song in the player's "Up next" queue (the queue carries no durations). */
data class QueueTrack(val id: Long, val title: String, val artist: String)

object Upcoming {
    /**
     * The [count] songs after the one playing. When the player doesn't report which queue item
     * is active, assume the queue starts with the current song.
     */
    fun next(queue: List<QueueTrack>, activeId: Long, count: Int): List<QueueTrack> {
        if (queue.isEmpty() || count <= 0) return emptyList()
        val index = queue.indexOfFirst { it.id == activeId }
        val start = if (index >= 0) index + 1 else 1
        return queue.drop(start).take(count)
    }
}
