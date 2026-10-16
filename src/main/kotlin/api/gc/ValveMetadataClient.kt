package api.gc

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import models.MatchDTO
import java.io.Closeable
import java.io.IOException

data class GcMetadataDownload(val matchId: Long, val clusterId: Long, val metadataSalt: Long) {
    fun url(): String {
        require(matchId > 0 && clusterId in 1..4294967295L && metadataSalt in 1..4294967295L)
        // Valve's replay servers publish this endpoint over HTTP; never send Steam credentials here.
        return "http://replay$clusterId.valve.net/1422450/${matchId}_$metadataSalt.meta.bz2"
    }
}

/** 403/404 usually mean Valve has not published the match file yet. */
class ValveMetadataHttpException(val status: Int) : IOException("Valve metadata HTTP $status")

class ValveMetadataClient(
    private val client: HttpClient = defaultClient(),
    private val maxDownloadBytes: Int = 8 * 1024 * 1024,
    private val maxDecodedBytes: Int = 32 * 1024 * 1024,
) : Closeable {
    init { require(maxDownloadBytes in 1 until Int.MAX_VALUE && maxDecodedBytes in 1 until Int.MAX_VALUE) }
    suspend fun download(reference: GcMetadataDownload): MatchDTO {
        return client.prepareGet(reference.url()).execute { response ->
            if (!response.status.isSuccess()) throw ValveMetadataHttpException(response.status.value)
            val bytes = response.bodyAsChannel().readRemaining(maxDownloadBytes.toLong() + 1).readByteArray()
            if (bytes.size > maxDownloadBytes) throw IOException("Valve metadata exceeds download size limit")
            withContext(Dispatchers.Default) { decodeValveMetadata(bytes, reference.matchId, maxDecodedBytes) }
        }
    }
    override fun close() = client.close()

    companion object {
        private fun defaultClient() = HttpClient(CIO) {
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 10_000
            }
        }
    }
}
