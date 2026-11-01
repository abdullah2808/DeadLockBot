package data

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import models.MatchDTO
import models.MatchHistoryDTO
import models.toMatchHistory
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInSubQuery
import org.jetbrains.exposed.sql.transactions.transaction

object CachedMatchTable : Table("cached_matches") {
    val matchId = long("match_id")
    val startTime = integer("start_time")
    val duration = integer("duration_s")
    val gameMode = integer("game_mode")
    val matchMode = integer("match_mode")
    val objectives0 = long("objectives_mask_team0")
    val objectives1 = long("objectives_mask_team1")
    val metadata = text("metadata").nullable()
    override val primaryKey = PrimaryKey(matchId)
}

object CachedPlayerMatchTable : Table("cached_player_matches") {
    val accountId = varchar("account_id", 50)
    val matchId = reference("match_id", CachedMatchTable.matchId)
    val heroId = integer("hero_id")
    val team = integer("team")
    val result = integer("match_result")
    val kills = integer("kills")
    val deaths = integer("deaths")
    val assists = integer("assists")
    val netWorth = integer("net_worth")
    val heroLevel = integer("hero_level")
    val lastHits = integer("last_hits")
    val denies = integer("denies")
    val abandonedTime = integer("abandoned_time_s").nullable()
    val teamAbandoned = bool("team_abandoned").nullable()
    val rankedDisplayBadge = integer("ranked_display_badge").nullable()
    val rankedDelta = integer("ranked_delta").nullable()
    val rankedCalibrationMatch = integer("ranked_calibration_match").nullable()
    val rankedUsedDemotionProtection = bool("ranked_used_demotion_protection").nullable()
    override val primaryKey = PrimaryKey(accountId, matchId)
}

object MatchCacheRepository {
    private val json = Json { ignoreUnknownKeys = true }
    /** Full match data is shared; only registered participants get history references. */
    fun saveEnrichedMetadata(match: MatchDTO, trackedAccounts: Set<String>): Unit = transaction {
        for (player in match.matchInfo.players) {
            val account = player.accountId.toUInt().toLong().toString()
            if (account in trackedAccounts) {
                match.toMatchHistory(account)?.let { summary ->
                    // API fallback may omit these fields: preserve the authoritative GC summary.
                    if (find(account, match.matchInfo.matchId) == null) save(account, listOf(summary))
                }
            }
        }
        saveMetadata(match)
    }
    fun save(account: String, matches: List<MatchHistoryDTO>): Unit = transaction {
        for (match in matches.sortedByDescending { it.matchId }.take(40)) {
            CachedMatchTable.upsert(CachedMatchTable.matchId, onUpdateExclude = listOf(CachedMatchTable.metadata)) {
                it[matchId] = match.matchId
                it[startTime] = match.startTime
                it[duration] = match.matchDurationSeconds
                it[gameMode] = match.gameMode
                it[matchMode] = match.matchMode
                it[objectives0] = match.objectivesMaskTeam0
                it[objectives1] = match.objectivesMaskTeam1
            }
            CachedPlayerMatchTable.upsert(CachedPlayerMatchTable.accountId, CachedPlayerMatchTable.matchId) {
                it[accountId] = account
                it[matchId] = match.matchId
                it[heroId] = match.heroId
                it[team] = match.playerTeam
                it[result] = match.matchResult
                it[kills] = match.kills
                it[deaths] = match.deaths
                it[assists] = match.assists
                it[netWorth] = match.netWorth
                it[heroLevel] = match.heroLevel
                it[lastHits] = match.lastHits
                it[denies] = match.denies
                it[abandonedTime] = match.abandonedTimeSeconds
                it[teamAbandoned] = match.teamAbandoned
                it[rankedDisplayBadge] = match.rankedDisplayBadge
                it[rankedDelta] = match.rankedDelta
                it[rankedCalibrationMatch] = match.rankedCalibrationMatch
                it[rankedUsedDemotionProtection] = match.rankedUsedDemotionProtection
            }
        }
        val excess = CachedPlayerMatchTable.selectAll().where { CachedPlayerMatchTable.accountId eq account }
            .orderBy(CachedPlayerMatchTable.matchId to SortOrder.DESC).drop(40)
            .map { it[CachedPlayerMatchTable.matchId] }
        if (excess.isNotEmpty()) CachedPlayerMatchTable.deleteWhere {
            (accountId eq account) and (matchId inList excess)
        }
        // Other party members keep shared data alive after this account trims its history.
        CachedMatchTable.deleteWhere { matchId notInSubQuery CachedPlayerMatchTable.select(CachedPlayerMatchTable.matchId) }
        Unit
    }

    fun find(account: String, id: Long): MatchHistoryDTO? = transaction {
        (CachedPlayerMatchTable innerJoin CachedMatchTable).selectAll().where {
            (CachedPlayerMatchTable.accountId eq account) and (CachedPlayerMatchTable.matchId eq id)
        }.singleOrNull()?.let { row ->
            MatchHistoryDTO(
                accountId = account.toLong().toInt(), denies = row[CachedPlayerMatchTable.denies],
                gameMode = row[CachedMatchTable.gameMode], heroId = row[CachedPlayerMatchTable.heroId],
                heroLevel = row[CachedPlayerMatchTable.heroLevel], lastHits = row[CachedPlayerMatchTable.lastHits],
                matchDurationSeconds = row[CachedMatchTable.duration], matchId = id,
                matchMode = row[CachedMatchTable.matchMode], matchResult = row[CachedPlayerMatchTable.result],
                netWorth = row[CachedPlayerMatchTable.netWorth], objectivesMaskTeam0 = row[CachedMatchTable.objectives0],
                objectivesMaskTeam1 = row[CachedMatchTable.objectives1], assists = row[CachedPlayerMatchTable.assists],
                deaths = row[CachedPlayerMatchTable.deaths], kills = row[CachedPlayerMatchTable.kills],
                playerTeam = row[CachedPlayerMatchTable.team], startTime = row[CachedMatchTable.startTime],
                abandonedTimeSeconds = row[CachedPlayerMatchTable.abandonedTime], teamAbandoned = row[CachedPlayerMatchTable.teamAbandoned],
                rankedDisplayBadge = row[CachedPlayerMatchTable.rankedDisplayBadge],
                rankedDelta = row[CachedPlayerMatchTable.rankedDelta],
                rankedCalibrationMatch = row[CachedPlayerMatchTable.rankedCalibrationMatch],
                rankedUsedDemotionProtection = row[CachedPlayerMatchTable.rankedUsedDemotionProtection],
            )
        }
    }

    /** Accounts with retained history for a match: the participants the bot can ask Valve on behalf of. */
    fun participants(id: Long): List<String> = transaction {
        CachedPlayerMatchTable.selectAll().where { CachedPlayerMatchTable.matchId eq id }
            .map { it[CachedPlayerMatchTable.accountId] }
    }

    fun findMetadata(id: Long): MatchDTO? = transaction {
        CachedMatchTable.selectAll().where { CachedMatchTable.matchId eq id }.singleOrNull()
            ?.get(CachedMatchTable.metadata)?.let { json.decodeFromString<MatchDTO>(it) }
    }

    fun saveMetadata(match: MatchDTO) = transaction {
        CachedMatchTable.update({ CachedMatchTable.matchId eq match.matchInfo.matchId }) {
            it[metadata] = json.encodeToString(match)
        }
        Unit
    }
}
