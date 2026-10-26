package com.aalyrics.source

import org.junit.Assert.assertEquals
import org.junit.Test

class UpcomingTest {
    private val queue = (1L..10L).map { QueueTrack(it, "Song $it", "Artist") }

    @Test
    fun takesTheSongsAfterTheActiveOne() {
        assertEquals(listOf(5L, 6L, 7L), Upcoming.next(queue, activeId = 4, count = 3).map { it.id })
    }

    @Test
    fun stopsAtTheEndOfTheQueue() {
        assertEquals(listOf(10L), Upcoming.next(queue, activeId = 9, count = 5).map { it.id })
        assertEquals(emptyList<Long>(), Upcoming.next(queue, activeId = 10, count = 5).map { it.id })
    }

    @Test
    fun unknownActiveItemAssumesQueueStartsWithCurrentSong() {
        assertEquals(listOf(2L, 3L), Upcoming.next(queue, activeId = -1, count = 2).map { it.id })
    }

    @Test
    fun emptyQueue() {
        assertEquals(emptyList<QueueTrack>(), Upcoming.next(emptyList(), activeId = 1, count = 5))
    }
}
