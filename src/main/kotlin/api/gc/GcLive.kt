package api.gc

import gc.citadel.CitadelLive
import models.ActiveMatchDTO
import models.ActiveMatchPlayer
import java.io.IOException

data class ProfileHero(val heroId: Int, val wins: Long, val kills: Long)

/** A player's in-game profile card: ranked badge, featured heroes and headline stats. */
data class ProfileCard(
    val accountId: Long,
    /** tier * 10 + subrank; null when Valve reports no rank (unranked or hidden). */
    val rankedBadgeLevel: Int?,
    val heroes: List<ProfileHero>,
    val gamesPlayed: Long? = null,
    val wins: Long? = null,
    val kills: Long? = null,
)

internal fun CitadelLive.CMsgCitadelProfileCard.toProfileCard(requestedAccountId: String): ProfileCard {
    val requested = requestedAccountId.toLong()
    val reported = accountId.toUInt().toLong()
    // A card for someone else must never be shown under the requested name.
    if (hasAccountId() && reported != 0L && reported != requested)
        throw IOException("GC returned profile card for $reported instead of $requested")
    val ordered = slotsList.sortedBy { it.slotId.toUInt() }
    val stats = ordered.filter { it.hasStat() }.associate { it.stat.statId to it.stat.statScore.toUInt().toLong() }
    return ProfileCard(
        accountId = requested,
        rankedBadgeLevel = rankedBadgeLevel.takeIf { hasRankedBadgeLevel() && it > 0 },
        heroes = ordered.filter { it.hasHero() && it.hero.heroId != 0 }.map {
            ProfileHero(it.hero.heroId, it.hero.heroWins.toUInt().toLong(), it.hero.heroKills.toUInt().toLong())
        },
        wins = stats[1],
        kills = stats[2],
        gamesPlayed = stats[3],
    )
}

internal fun CitadelLive.CMsgDevMatchInfo.toActiveMatch() = ActiveMatchDTO(
    matchId = matchId,
    startTime = startTime.toUInt().toLong(),
    players = playersList.map {
        ActiveMatchPlayer(it.accountId.toUInt().toLong(), it.team, it.heroId, it.abandoned)
    },
    durationS = durationS.toUInt().toLong(),
    spectators = spectators.toUInt().toLong(),
    netWorthTeam0 = netWorthTeam0.toUInt().toLong(),
    netWorthTeam1 = netWorthTeam1.toUInt().toLong(),
    gameMode = gameMode,
    matchMode = matchMode,
)

private const val MAX_ACTIVE_MATCHES_BYTES = 32 * 1024 * 1024

/**
 * Decode the Watch-tab list. Other GC clients report this body arriving Snappy-compressed (sometimes behind a
 * short prefix), so a plain parse that yields no matches but unknown data falls back to those layouts.
 */
internal fun decodeActiveMatches(body: ByteArray): CitadelLive.CMsgClientToGCGetActiveMatchesResponse {
    val plain = runCatching { CitadelLive.CMsgClientToGCGetActiveMatchesResponse.parseFrom(body) }.getOrNull()
    if (plain != null && (plain.activeMatchesCount > 0 || body.isEmpty())) return plain
    for (offset in intArrayOf(0, 7)) {
        if (body.size <= offset) continue
        val decompressed = runCatching { snappyDecompress(body, offset, MAX_ACTIVE_MATCHES_BYTES) }.getOrNull() ?: continue
        val parsed = runCatching { CitadelLive.CMsgClientToGCGetActiveMatchesResponse.parseFrom(decompressed) }.getOrNull()
        if (parsed != null && parsed.activeMatchesCount > 0) return parsed
    }
    return plain ?: throw IOException("Could not decode GC active matches (${body.size} bytes)")
}

/** Raw (unframed) Snappy block decoder; bounded so a malformed length cannot exhaust memory. */
internal fun snappyDecompress(input: ByteArray, start: Int = 0, maxLength: Int = MAX_ACTIVE_MATCHES_BYTES): ByteArray {
    var pos = start
    fun byte(): Int {
        if (pos >= input.size) throw IOException("Truncated Snappy data")
        return input[pos++].toInt() and 0xFF
    }
    fun littleEndian(count: Int): Long {
        var value = 0L
        for (i in 0 until count) value = value or (byte().toLong() shl (8 * i))
        return value
    }
    var length = 0L
    var shift = 0
    while (true) {
        val b = byte()
        length = length or ((b and 0x7F).toLong() shl shift)
        if (b and 0x80 == 0) break
        shift += 7
        if (shift > 28) throw IOException("Invalid Snappy length")
    }
    if (length > maxLength) throw IOException("Snappy data too large: $length bytes")
    val out = ByteArray(length.toInt())
    var written = 0
    while (pos < input.size) {
        val tag = byte()
        when (tag and 3) {
            0 -> {
                val code = tag ushr 2
                val literal = (if (code < 60) code.toLong() else littleEndian(code - 59)) + 1
                if (literal > input.size - pos || literal > out.size - written) throw IOException("Invalid Snappy literal")
                System.arraycopy(input, pos, out, written, literal.toInt())
                pos += literal.toInt()
                written += literal.toInt()
            }
            else -> {
                val (copyLength, offset) = when (tag and 3) {
                    1 -> (4 + ((tag ushr 2) and 7)) to (((tag ushr 5).toLong() shl 8) or byte().toLong())
                    2 -> ((tag ushr 2) + 1) to littleEndian(2)
                    else -> ((tag ushr 2) + 1) to littleEndian(4)
                }
                if (offset <= 0 || offset > written || copyLength > out.size - written) throw IOException("Invalid Snappy copy")
                // Copies may overlap their own output, so copy byte by byte.
                for (i in 0 until copyLength) out[written + i] = out[written - offset.toInt() + i]
                written += copyLength
            }
        }
    }
    if (written != out.size) throw IOException("Snappy data ended early")
    return out
}
