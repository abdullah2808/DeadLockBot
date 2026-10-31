package data

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import models.MatchHistoryDTO
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.transactions.transaction

object WeeklySettingsTable : Table("weekly_digest_settings") {
    val discordId = varchar("discord_id", 32)
    val accountId = varchar("account_id", 50)
    val channelId = varchar("channel_id", 32)
    val enabledAt = long("enabled_at")
    override val primaryKey = PrimaryKey(discordId)
}
object WeeklyMatchTable : Table("weekly_digest_matches") {
    val accountId = varchar("account_id", 50)
    val matchId = long("match_id")
    val startTime = long("start_time")
    val summary = text("summary")
    override val primaryKey = PrimaryKey(accountId, matchId)
}
object WeeklyDeliveryTable : Table("weekly_digest_deliveries") {
    val channelId = varchar("channel_id", 32)
    val weekStart = long("week_start")
    override val primaryKey = PrimaryKey(channelId, weekStart)
}
object WeeklyDigestRepository {
    private val json = Json { ignoreUnknownKeys = true }
    fun enable(user: UserRepository.UserRecord): Unit = transaction {
        WeeklySettingsTable.upsert(onUpdateExclude = listOf(WeeklySettingsTable.enabledAt)) {
            it[discordId] = user.discordId; it[accountId] = user.accountId
            it[channelId] = requireNotNull(user.channelId); it[enabledAt] = System.currentTimeMillis() / 1000
        }
        Unit
    }
    fun disable(discord: String): Unit = transaction { WeeklySettingsTable.deleteWhere { discordId eq discord }; Unit }
    fun subscribers(users: List<UserRepository.UserRecord>, eligibleBefore: Long = Long.MAX_VALUE): List<UserRepository.UserRecord> = transaction {
        val settings = WeeklySettingsTable.selectAll().associateBy { it[WeeklySettingsTable.discordId] }
        users.filter { user -> settings[user.discordId]?.let { it[WeeklySettingsTable.accountId] == user.accountId &&
            it[WeeklySettingsTable.channelId] == user.channelId && it[WeeklySettingsTable.enabledAt] < eligibleBefore } == true }
    }
    /** Separate rolling archive so weekly coverage does not depend on the 25-row report cache. */
    fun record(account: String, matches: List<MatchHistoryDTO>, now: Long = System.currentTimeMillis() / 1000): Unit = transaction {
        // Archive only opted-in players. Unsubscribing stops collection on the next history refresh.
        if (!WeeklySettingsTable.selectAll().where { WeeklySettingsTable.accountId eq account }.empty()) {
            matches.filter { it.startTime.toLong() in (now - 21 * 86400)..now }.forEach { match ->
                WeeklyMatchTable.upsert {
                    it[accountId] = account; it[matchId] = match.matchId
                    it[startTime] = match.startTime.toLong(); it[summary] = json.encodeToString(match)
                }
            }
        }
        WeeklyMatchTable.deleteWhere { startTime less (now - 21 * 86400) }
        Unit
    }
    fun matches(account: String, from: Long, until: Long): List<MatchHistoryDTO> = transaction {
        WeeklyMatchTable.selectAll().where { (WeeklyMatchTable.accountId eq account) and
            (WeeklyMatchTable.startTime greaterEq from) and (WeeklyMatchTable.startTime less until) }
            .map { json.decodeFromString<MatchHistoryDTO>(it[WeeklyMatchTable.summary]) }
    }
    fun delivered(channel: String, from: Long): Boolean = transaction {
        !WeeklyDeliveryTable.selectAll().where { (WeeklyDeliveryTable.channelId eq channel) and (WeeklyDeliveryTable.weekStart eq from) }.empty()
    }
    fun markDelivered(channel: String, from: Long): Unit = transaction {
        WeeklyDeliveryTable.upsert { it[channelId] = channel; it[weekStart] = from }
        WeeklyDeliveryTable.deleteWhere { weekStart less (from - 28 * 86400) }
        Unit
    }
}
