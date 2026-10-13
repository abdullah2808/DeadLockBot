package models

import kotlinx.serialization.json.Json
import java.net.URI

internal val assetJson = Json { ignoreUnknownKeys = true }

internal fun readAssetCatalog(resource: String): String {
    val connection = URI("https://api.deadlock-api.com/v1/assets/$resource").toURL().openConnection().apply {
        connectTimeout = 10_000
        readTimeout = 10_000
    }
    return connection.getInputStream().bufferedReader().use { it.readText() }
}

/** Refresh missing entries and expired catalogs without repeatedly requesting an unavailable API. */
internal class AssetCatalog<K, V>(
    private val load: () -> Map<K, V>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = 6 * 60 * 60 * 1000L,
    private val refreshIntervalMillis: Long = 60_000L,
    private val onFailure: (Exception) -> Unit = { println("Asset catalog refresh failed: ${it.message}") },
) {
    private var entries: Map<K, V> = emptyMap()
    private var loadedAt: Long? = null
    private var attemptedAt: Long? = null

    @Synchronized
    fun get(key: K): V? {
        val now = clock()
        val expired = loadedAt?.let { now - it >= ttlMillis } ?: true
        val canRefresh = attemptedAt?.let { now - it >= refreshIntervalMillis } ?: true
        if ((expired || key !in entries) && canRefresh) {
            attemptedAt = now
            try {
                val updated = load()
                require(updated.isNotEmpty()) { "Asset catalog was empty" }
                entries = updated
                loadedAt = now
            } catch (e: Exception) {
                onFailure(e)
            }
        }
        return entries[key]
    }
}
