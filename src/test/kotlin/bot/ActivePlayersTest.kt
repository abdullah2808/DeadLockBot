package bot

import models.ActiveMatchDTO
import models.ActiveMatchPlayer
import kotlin.test.*

class ActivePlayersTest {
    private val alice = ChannelPlayer(1, "Alice", null)
    private val bob = ChannelPlayer(2, "Bob", true)
    private val carol = ChannelPlayer(3, "Carol", true)
    private val dan = ChannelPlayer(4, "Dan", false)
    private val erin = ChannelPlayer(5, "Erin", null)

    @Test fun `players sharing a match are grouped, and everyone else gets their best known status`() {
        val board = activeBoard(listOf(erin, dan, carol, bob, alice), listOf(
            ActiveMatchDTO(matchId = 20, startTime = 200, players = listOf(ActiveMatchPlayer(99, 0, 1))),
            ActiveMatchDTO(matchId = 10, startTime = 100, players = listOf(ActiveMatchPlayer(2, 1, 8), ActiveMatchPlayer(1, 0, 7), ActiveMatchPlayer(98, 0, 2))),
        ))
        assertEquals(listOf(10L), board.listed.map { it.match.matchId })
        assertEquals(listOf("Alice", "Bob"), board.listed.single().players.map { it.player.name }, "ordered by team")
        assertEquals(listOf(7, 8), board.listed.single().players.map { it.seat.heroId })
        assertEquals(listOf(carol), board.inDeadlock, "a listed match outranks Steam presence")
        assertEquals(listOf(dan, erin), board.notSeen)
    }

    @Test fun `separate matches stay separate, oldest first`() {
        val board = activeBoard(listOf(alice, erin), listOf(
            ActiveMatchDTO(matchId = 20, startTime = 200, players = listOf(ActiveMatchPlayer(5))),
            ActiveMatchDTO(matchId = 10, startTime = 100, players = listOf(ActiveMatchPlayer(1))),
        ))
        assertEquals(listOf(10L, 20L), board.listed.map { it.match.matchId })
    }

    @Test fun `someone who left one listed match and joined another shows in the one they are still in`() {
        val board = activeBoard(listOf(alice), listOf(
            ActiveMatchDTO(matchId = 10, players = listOf(ActiveMatchPlayer(1, abandoned = true))),
            ActiveMatchDTO(matchId = 20, players = listOf(ActiveMatchPlayer(1, abandoned = null))),
        ))
        assertEquals(20, board.listed.single().match.matchId)
    }

    @Test fun `an unavailable or empty list leaves only presence to report`() {
        val board = activeBoard(listOf(bob, erin), emptyList())
        assertTrue(board.listed.isEmpty())
        assertEquals(listOf(bob), board.inDeadlock)
        assertEquals(listOf(erin), board.notSeen)
    }
}
