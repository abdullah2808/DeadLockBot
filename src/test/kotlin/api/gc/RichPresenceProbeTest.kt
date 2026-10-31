package api.gc

import com.google.protobuf.ByteString
import `in`.dragonbra.javasteam.base.ClientMsgProtobuf
import `in`.dragonbra.javasteam.enums.EMsg
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserver2.CMsgClientRichPresenceInfo
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserver2.CMsgClientRichPresenceRequest
import `in`.dragonbra.javasteam.steam.CMClient
import `in`.dragonbra.javasteam.types.KeyValue
import java.io.ByteArrayOutputStream
import kotlin.test.*

class RichPresenceProbeTest {
    private val logged = mutableListOf<String>()
    private var now = 0L
    private val probe = RichPresenceProbe(isWatched = { it == "107" }, log = { logged += it }, clock = { now }, pollIntervalMillis = 30_000)

    @Test fun `accounts that aren't tracked friends are never logged`() {
        probe.observe("persona", "999", listOf("status" to "secret"), appId = 1422450)
        assertTrue(logged.isEmpty())
    }

    @Test fun `only changes are logged, per route, so a long match stays readable`() {
        probe.observe("persona", "107", listOf("steam_display" to "#a"), appId = 1422450)
        probe.observe("persona", "107", listOf("steam_display" to "#a"), appId = 1422450)
        assertEquals(1, logged.size)
        assertEquals("Rich presence probe [persona] account=107 app=1422450: steam_display=\"#a\"", logged.single())
        probe.observe("rich-presence-info", "107", listOf("steam_display" to "#a"))
        assertEquals(2, logged.size)
        probe.observe("persona", "107", listOf("steam_display" to "#b"), appId = 1422450)
        assertEquals(3, logged.size)
    }

    @Test fun `leaving the game counts as a change even with the same values`() {
        probe.observe("persona", "107", emptyList(), appId = 1422450)
        probe.observe("persona", "107", emptyList(), appId = 0)
        assertEquals(2, logged.size)
    }

    @Test fun `an empty answer is logged, because that is the evidence if Steam withholds it`() {
        probe.observe("rich-presence-info", "107", emptyList())
        assertContains(logged.single(), "(none)")
    }

    @Test fun `join strings are redacted, and other people's text can't forge or flood log lines`() {
        val line = formatRichPresence(listOf("Connect" to "+connect secret", "status" to "a\nRich presence probe: fake", "long" to "x".repeat(500)))
        assertContains(line, "Connect=\"[redacted]\"")
        assertFalse(line.contains("secret"))
        assertFalse(line.contains('\n'))
        assertContains(line, "x".repeat(200) + "\"")
        assertFalse(line.contains("x".repeat(201)))
    }

    @Test fun `polls are spaced out and the friend count is logged only when it changes`() {
        assertTrue(probe.pollDue())
        assertFalse(probe.pollDue())
        now = 29_999
        assertFalse(probe.pollDue())
        now = 30_000
        assertTrue(probe.pollDue())
        probe.polling(0)
        probe.polling(0)
        probe.polling(2)
        assertEquals(listOf("Rich presence probe: asking about 0 tracked friends in Deadlock.",
            "Rich presence probe: asking about 2 tracked friends in Deadlock."), logged)
    }

    private fun binaryKv(vararg values: Pair<String, String>): ByteString {
        val root = KeyValue("RP")
        values.forEach { (key, value) -> root.children.add(KeyValue(key, value)) }
        return ByteArrayOutputStream().use { root.saveToStream(it, true); ByteString.copyFrom(it.toByteArray()) }
    }

    @Test fun `values decode from the binary blob and the repeated field`() {
        val entry = CMsgClientRichPresenceInfo.RichPresence.newBuilder()
            .setRichPresenceKv(binaryKv("steam_display" to "#Steam_Citadel_InGame_Hero", "param0" to "12"))
            .addRichPresense(CMsgClientRichPresenceInfo.KV.newBuilder().setKey("status").setValue("In game"))
            .build()
        assertEquals(setOf("steam_display" to "#Steam_Citadel_InGame_Hero", "param0" to "12", "status" to "In game"), richPresenceValues(entry).toSet())
    }

    @Test fun `a serialized response reaches the probe and other Steam messages are ignored`() {
        val response = ClientMsgProtobuf<CMsgClientRichPresenceInfo.Builder>(CMsgClientRichPresenceInfo::class.java, EMsg.ClientRichPresenceInfo)
        response.body.addRichPresence(CMsgClientRichPresenceInfo.RichPresence.newBuilder()
            .setSteamidUser(76561197960265728L + 107)
            .setRichPresenceKv(binaryKv("steam_display" to "#a")))
        response.body.addRichPresence(CMsgClientRichPresenceInfo.RichPresence.newBuilder().setSteamidUser(76561197960265728L + 999))
        val handler = RichPresenceInfoHandler(probe)
        handler.handleMsg(CMClient.getPacketMsg(response.serialize())!!)
        assertEquals(listOf("Rich presence probe [rich-presence-info] account=107: steam_display=\"#a\""), logged)

        handler.handleMsg(CMClient.getPacketMsg(richPresenceRequest(1, listOf(1L)).serialize())!!)
        assertEquals(1, logged.size)
    }

    @Test fun `the request names the friends and routes to Deadlock on the wire`() {
        val sent = richPresenceRequest(1422450, listOf(76561197960265835L)).serialize()
        val parsed = ClientMsgProtobuf<CMsgClientRichPresenceRequest.Builder>(CMsgClientRichPresenceRequest::class.java, CMClient.getPacketMsg(sent)!!)
        assertEquals(EMsg.ClientRichPresenceRequest, parsed.msgType)
        assertEquals(1422450, parsed.protoHeader.routingAppid)
        assertEquals(listOf(76561197960265835L), parsed.body.steamidRequestList)
    }

    @Test fun `the client builds with the probe on and off without touching the network`() {
        GcClient("user", null, "token", richPresenceProbe = true)
        GcClient("user", null, "token")
    }

    @Test fun `a malformed blob or an empty entry yields no values instead of failing`() {
        val malformed = CMsgClientRichPresenceInfo.RichPresence.newBuilder().setRichPresenceKv(ByteString.copyFrom(byteArrayOf(0, 'R'.code.toByte()))).build()
        assertTrue(richPresenceValues(malformed).isEmpty())
        assertTrue(richPresenceValues(CMsgClientRichPresenceInfo.RichPresence.getDefaultInstance()).isEmpty())
    }
}
