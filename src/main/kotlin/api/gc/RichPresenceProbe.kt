package api.gc

import `in`.dragonbra.javasteam.base.ClientMsgProtobuf
import `in`.dragonbra.javasteam.base.IPacketMsg
import `in`.dragonbra.javasteam.enums.EMsg
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserver2.CMsgClientRichPresenceInfo
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserver2.CMsgClientRichPresenceRequest
import `in`.dragonbra.javasteam.steam.handlers.ClientMsgHandler
import `in`.dragonbra.javasteam.types.KeyValue
import `in`.dragonbra.javasteam.types.SteamID
import java.util.concurrent.ConcurrentHashMap

/**
 * Off-by-default diagnostic for finding out what Steam rich presence reports for friends of the bot.
 * It only logs: nothing reads these values yet. Only watched accounts are logged, and only when a value changes.
 */
internal class RichPresenceProbe(
    private val isWatched: (String) -> Boolean,
    private val log: (String) -> Unit = ::println,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollIntervalMillis: Long = 30_000L,
) {
    private val lastSeen = ConcurrentHashMap<String, String>()
    private var nextPollAt = Long.MIN_VALUE
    private var lastPollSize: Int? = null

    fun pollDue(): Boolean {
        val now = clock()
        if (now < nextPollAt) return false
        nextPollAt = now + pollIntervalMillis
        return true
    }

    /** Separates "no friend was in Deadlock to ask about" from "asked, but Steam sent nothing back". */
    fun polling(friendsInDeadlock: Int) {
        if (friendsInDeadlock != lastPollSize) log("Rich presence probe: asking about $friendsInDeadlock tracked friends in Deadlock.")
        lastPollSize = friendsInDeadlock
    }

    /** An empty [values] is logged too, because that is the evidence if Steam withholds rich presence. */
    fun observe(route: String, accountId: String, values: List<Pair<String, String>>, appId: Int? = null) {
        if (!isWatched(accountId)) return
        val line = formatRichPresence(values)
        val state = "${appId ?: ""}|$line"
        if (lastSeen.put("$route|$accountId", state) == state) return
        log("Rich presence probe [$route] account=$accountId${appId?.let { " app=$it" } ?: ""}: $line")
    }
}

internal class RichPresenceInfoHandler(private val probe: RichPresenceProbe) : ClientMsgHandler() {
    override fun handleMsg(packetMsg: IPacketMsg) {
        if (packetMsg.msgType != EMsg.ClientRichPresenceInfo) return
        try {
            val info = ClientMsgProtobuf<CMsgClientRichPresenceInfo.Builder>(CMsgClientRichPresenceInfo::class.java, packetMsg)
            info.body.richPresenceList.forEach { entry ->
                probe.observe("rich-presence-info", SteamID(entry.steamidUser).accountID.toString(), richPresenceValues(entry))
            }
        } catch (e: Exception) {
            println("Rich presence probe: could not read a response (${e.javaClass.simpleName}).")
        }
    }
}

/** Rich presence belongs to an app, so the request is routed to it. */
internal fun richPresenceRequest(appId: Int, steamIds: List<Long>) =
    ClientMsgProtobuf<CMsgClientRichPresenceRequest.Builder>(CMsgClientRichPresenceRequest::class.java, EMsg.ClientRichPresenceRequest).apply {
        protoHeader.routingAppid = appId
        body.addAllSteamidRequest(steamIds)
    }

private const val MAX_LOGGED_VALUE_LENGTH = 200
// A connect string can let someone join the player's game, so it never reaches the logs.
private val REDACTED_KEYS = setOf("connect")

/** Values are written by other people's game clients, so they are flattened and bounded before logging. */
internal fun formatRichPresence(values: List<Pair<String, String>>): String {
    if (values.isEmpty()) return "(none)"
    return values.sortedBy { it.first }.joinToString(", ") { (key, value) ->
        val shown = if (key.lowercase() in REDACTED_KEYS) "[redacted]"
        else value.replace(Regex("[\\p{Cntrl}]"), " ").take(MAX_LOGGED_VALUE_LENGTH)
        "${key.replace(Regex("[\\p{Cntrl}]"), " ").take(MAX_LOGGED_VALUE_LENGTH)}=\"$shown\""
    }
}

/** Steam may answer with a binary KeyValues blob, a repeated key/value field, or both. */
internal fun richPresenceValues(entry: CMsgClientRichPresenceInfo.RichPresence): List<Pair<String, String>> {
    val binary = if (entry.hasRichPresenceKv() && !entry.richPresenceKv.isEmpty) try {
        KeyValue().also { it.tryReadAsBinary(entry.richPresenceKv.newInput()) }.leaves()
    } catch (e: Exception) {
        println("Rich presence probe: could not decode a KeyValues blob (${e.javaClass.simpleName}, ${entry.richPresenceKv.size()} bytes).")
        emptyList()
    } else emptyList()
    return binary + entry.richPresenseList.map { it.key to it.value }
}

private fun KeyValue.leaves(): List<Pair<String, String>> =
    if (children.isNotEmpty()) children.flatMap { it.leaves() }
    else listOfNotNull(name?.let { key -> value?.let { key to it } })
