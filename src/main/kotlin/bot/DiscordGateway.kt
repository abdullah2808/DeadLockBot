package bot

import dev.kord.gateway.DefaultGateway
import kotlinx.serialization.json.Json

/**
 * Kord 0.18.1 still treats a channel's nullable application_id as a non-null
 * OptionalSnowflake, and exposes no gateway JSON configuration. Treat null
 * optional fields as absent, preserving the rest of Kord's parser settings.
 * This JVM workaround is covered against the pinned Kord version by tests;
 * remove it when Kord supports this payload or exposes parser configuration.
 */
internal fun DefaultGateway.acceptNullableChannelApplicationIds(): DefaultGateway = apply {
    val parserField = DefaultGateway::class.java.getDeclaredField("jsonParser")
    parserField.isAccessible = true
    val original = parserField.get(this) as Json
    parserField.set(this, Json(original) { coerceInputValues = true })
}
