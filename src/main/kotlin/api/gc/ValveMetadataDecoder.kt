package api.gc

import com.github.luben.zstd.ZstdInputStream
import gc.citadel.CitadelMetadata.CMsgMatchMetaData
import gc.citadel.CitadelMetadata.CMsgMatchMetaDataContents
import models.MatchDTO
import models.MatchInfoDTO
import models.Players
import models.Stats
import models.MatchItem
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.ByteArrayInputStream
import java.io.IOException

/** Valve still names zstd metadata downloads .meta.bz2; detect the actual bytes. */
internal fun decodeValveMetadata(bytes: ByteArray, matchId: Long, maxDecodedBytes: Int = 32 * 1024 * 1024): MatchDTO {
    require(matchId > 0 && maxDecodedBytes in 1 until Int.MAX_VALUE)
    val source = ByteArrayInputStream(bytes)
    val decoded = when {
        bytes.take(4) == listOf(0x28.toByte(), 0xb5.toByte(), 0x2f.toByte(), 0xfd.toByte()) ->
            ZstdInputStream(source).setLongMax(25) // Limit the native decoder's window to 32 MiB as well.
        bytes.take(3) == listOf('B'.code.toByte(), 'Z'.code.toByte(), 'h'.code.toByte()) ->
            BZip2CompressorInputStream(source)
        else -> source
    }.use { it.readNBytes(maxDecodedBytes + 1) }
    if (decoded.size > maxDecodedBytes) throw IOException("Valve metadata exceeds decoded size limit")
    val envelope = CMsgMatchMetaData.parseFrom(decoded)
    require(envelope.hasMatchId() && envelope.matchId == matchId) { "Valve metadata envelope has a different match ID" }
    val contents = CMsgMatchMetaDataContents.parseFrom(envelope.matchDetails)
    require(contents.hasMatchInfo()) { "Valve metadata has no match information" }
    val info = contents.matchInfo
    require(info.hasMatchId() && info.matchId == matchId) { "Valve metadata contents have a different match ID" }
    require(info.hasDurationS() && info.hasStartTime() && info.startTime > 0 && info.playersCount > 0) {
        "Valve metadata is incomplete"
    }
    require(info.matchOutcome != 1) { "Valve metadata describes an invalid match" }
    val players = info.playersList.filter { it.hasAccountId() && it.accountId != 0 && it.heroId > 0 }.map { player ->
        val last = player.statsList.asReversed().maxByOrNull { it.timeStampS }
        Players(
            accountId = player.accountId, team = player.team, heroId = player.heroId,
            kills = if (player.hasKills()) player.kills else last?.kills ?: 0,
            deaths = if (player.hasDeaths()) player.deaths else last?.deaths ?: 0,
            assists = if (player.hasAssists()) player.assists else last?.assists ?: 0,
            netWorth = player.netWorth, lastHits = player.lastHits, denies = player.denies,
            heroLevel = player.level,
            abandonedTimeSeconds = player.abandonMatchTimeS.takeIf { player.hasAbandonMatchTimeS() },
            playerSlot = player.playerSlot,
            items = player.itemsList.map { item -> MatchItem(item.itemId.toUInt().toLong(), item.gameTimeS,
                item.soldTimeS.takeIf { item.hasSoldTimeS() }) },
            stats = last?.let { listOf(Stats(
                it.playerDamage.takeIf { _ -> it.hasPlayerDamage() },
                it.bossDamage.takeIf { _ -> it.hasBossDamage() },
                it.playerHealing.takeIf { _ -> it.hasPlayerHealing() },
                it.damageAbsorbed.takeIf { _ -> it.hasDamageAbsorbed() },
                timeStampSeconds = it.timeStampS
            )) }
                ?: emptyList(),
        )
    }
    require(players.isNotEmpty()) { "Valve metadata has no identifiable participants" }
    return MatchDTO(MatchInfoDTO(
        matchId = matchId, startTime = info.startTime, durationSeconds = info.durationS,
        winningTeam = if (info.matchOutcome != 0 || info.notScored) -1 else info.winningTeam,
        players = players, gameMode = info.gameMode, matchMode = info.matchMode,
        objectivesMaskTeam0 = info.objectivesMaskTeam0, objectivesMaskTeam1 = info.objectivesMaskTeam1,
        averageBadgeTeam0 = info.averageBadgeTeam0, averageBadgeTeam1 = info.averageBadgeTeam1,
        matchOutcome = info.matchOutcome,
    ))
}
