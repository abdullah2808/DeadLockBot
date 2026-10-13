package api

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import util.AccountId
import java.io.Closeable
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

sealed interface AccountResolution {
    data class Resolved(val accountId: String) : AccountResolution
    data object Invalid : AccountResolution
    data object Unavailable : AccountResolution
}

/** Resolves public Steam profile identity; Deadlock account verification remains separate. */
class SteamAccountResolver(private val client: HttpClient = defaultHttpClient()) : Closeable {
    suspend fun resolve(raw: String): AccountResolution {
        AccountId.normalize(raw)?.let { return AccountResolution.Resolved(it) }
        val name = AccountId.vanityName(raw) ?: return AccountResolution.Invalid
        return try {
            // Reconstruct a fixed Steam origin instead of requesting the supplied URL.
            val response = client.get("https://steamcommunity.com/id/$name/") {
                parameter("xml", "1")
            }
            when {
                response.status == HttpStatusCode.NotFound -> AccountResolution.Invalid
                !response.status.isSuccess() -> AccountResolution.Unavailable
                else -> parseProfile(response.bodyAsText())?.accountId?.let(AccountResolution::Resolved)
                    ?: AccountResolution.Unavailable
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            AccountResolution.Unavailable
        }
    }

    /** Optional display-name lookup; verify the response belongs to the requested numeric account. */
    suspend fun playerName(rawAccount: String): String? {
        val account = AccountId.normalize(rawAccount) ?: return null
        return try {
            withTimeoutOrNull(3_000) {
                val steamId64 = 76561197960265728L + account.toLong()
                client.prepareGet("https://steamcommunity.com/profiles/$steamId64/") { parameter("xml", "1") }
                    .execute { response ->
                        if (!response.status.isSuccess()) return@execute null
                        val bytes = response.bodyAsChannel().readRemaining(65_537).readByteArray()
                        if (bytes.size > 65_536) return@execute null
                        parseProfile(bytes.decodeToString(throwOnInvalidSequence = true))
                            ?.takeIf { it.accountId == account }?.name
                    }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    }

    override fun close() = client.close()

    companion object {
        private fun defaultHttpClient() = HttpClient(CIO) {
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 10_000
            }
        }

        private data class Profile(val accountId: String, val name: String?)
        private fun parseProfile(xml: String): Profile? {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            val document = factory.newDocumentBuilder().apply { setErrorHandler(DefaultHandler()) }
                .parse(InputSource(StringReader(xml)))
            if (document.documentElement.tagName != "profile") return null
            val ids = document.documentElement.getElementsByTagName("steamID64")
            if (ids.length != 1) return null
            val steamId = ids.item(0).textContent.trim()
            val account = steamId.takeIf { it.length == 17 }?.let(AccountId::normalize) ?: return null
            val names = document.documentElement.getElementsByTagName("steamID")
            val name = if (names.length == 1) names.item(0).textContent else null
            return Profile(account, name)
        }
    }
}
