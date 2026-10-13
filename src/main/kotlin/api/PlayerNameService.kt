package api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import util.AccountId

/** Resolve the requested Steam account, never infer its name from the Discord requester. */
class PlayerNameService(
    private val steamName: (String) -> String? = { null },
    private val profileName: suspend (String) -> String? = { null },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private data class CachedName(val name: String?, val expires: Long)
    private val mutex = Mutex()
    private val cache = object : LinkedHashMap<String, CachedName>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedName>) = size > 256
    }

    suspend fun nameFor(rawAccount: String): String {
        val account = AccountId.normalize(rawAccount) ?: return "Unknown player"
        val sessionName = try { clean(steamName(account)) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        sessionName?.let { return it }
        return mutex.withLock {
            cache[account]?.takeIf { clock() < it.expires }?.let { return@withLock it.name ?: "Account $account" }
            val name = try { clean(profileName(account)) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
            // Retry unavailable names sooner, without hitting Steam on every repeated command.
            cache[account] = CachedName(name, clock() + if (name == null) 60_000L else 15 * 60_000L)
            name ?: "Account $account"
        }
    }

    private fun clean(name: String?): String? = name?.replace(Regex("[\\p{Cc}\\s]+"), " ")
        ?.trim()?.take(80)?.takeIf { it.isNotBlank() }
}

/** A Steam display name must be literal text inside the embed's bold title. */
internal fun String.asDiscordTitleText(): String = buildString {
    for (character in this@asDiscordTitleText) {
        if (character in "\\*_~`|[]()") append('\\')
        append(character)
    }
}
