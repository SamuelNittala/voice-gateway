package dev.nittala.voicegateway.core.api

import dev.nittala.voicegateway.core.model.ErrorBody
import dev.nittala.voicegateway.core.model.GatewayJson
import dev.nittala.voicegateway.core.model.offlineOf
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject

/**
 * Spec (A4), pure: the [Refusal] for an error answer.
 * - [body] is parsed as an error body; unknown fields are ignored. A body that is not JSON (a proxy's HTML page)
 *   gives a refusal with no reason and an empty message, never an exception.
 * - `reason` maps through [RefusalReason.of]; `status`, `output`, `screen_token`, `retry_after` are copied as given.
 * - `retryAfterSeconds` is the body's `retry_after`, else the [retryAfterHeader] when it is a whole number of
 *   seconds, else null.
 * - `offline` is read only with the reason `fetch_failed`, and only when it is a well-formed object; anything else
 *   gives null, never an exception.
 */
fun refusalOf(httpStatus: Int, body: String, retryAfterHeader: String? = null): Refusal {
    val parsed = try {
        GatewayJson.decodeFromString<ErrorBody>(body)
    } catch (_: SerializationException) {
        ErrorBody()
    } catch (_: IllegalArgumentException) {
        ErrorBody()
    }
    val reason = RefusalReason.of(parsed.reason)
    val headerSeconds = retryAfterHeader?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
    return Refusal(
        httpStatus = httpStatus,
        reason = reason,
        message = parsed.error,
        agentStatus = parsed.status,
        output = parsed.output,
        screenToken = parsed.screenToken,
        retryAfterSeconds = parsed.retryAfter ?: headerSeconds,
        offline = if (reason == RefusalReason.FetchFailed) offlineOf(parsed.offline as? JsonObject) else null,
    )
}
