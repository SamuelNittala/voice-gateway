package dev.nittala.voicegateway.core.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import java.io.File

/**
 * One gateway answer from src/test/resources/fixtures, written by android/tools/make_fixtures.py from the real
 * gateway.py views: `{"status", "headers", "body"}`.
 */
data class Fixture(val name: String, val status: Int, val headers: Map<String, String>, val body: JsonObject) {
    val bodyText: String get() = body.toString()

    fun response(): MockResponse = MockResponse().setResponseCode(status).apply {
        this@Fixture.headers.forEach { (name, value) -> addHeader(name, value) }
        addHeader("Content-Type", "application/json")
        setBody(bodyText)
    }
}

object Fixtures {
    private val dir: File by lazy {
        File(requireNotNull(Fixtures::class.java.getResource("/fixtures/sessions.json")) { "fixtures missing" }.toURI())
            .parentFile
    }

    fun load(name: String): Fixture {
        val root = Json.parseToJsonElement(File(dir, "$name.json").readText()).jsonObject
        return Fixture(
            name = name,
            status = root.getValue("status").jsonPrimitive.int,
            headers = root.getValue("headers").jsonObject.mapValues { it.value.jsonPrimitive.content },
            body = root.getValue("body").jsonObject,
        )
    }

    /** Every fixture whose name starts with [prefix], sorted. */
    fun all(prefix: String): List<Fixture> =
        dir.listFiles().orEmpty().map { it.name.removeSuffix(".json") }.filter { it.startsWith(prefix) }.sorted()
            .map(::load)
}
