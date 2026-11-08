package dev.nittala.voicegateway.core.config

import dev.nittala.voicegateway.core.api.originOf
import dev.nittala.voicegateway.core.config.BaseUrlRefusal.BACKSLASH
import dev.nittala.voicegateway.core.config.BaseUrlRefusal.BAD_HOST
import dev.nittala.voicegateway.core.config.BaseUrlRefusal.BAD_PORT
import dev.nittala.voicegateway.core.config.BaseUrlRefusal.BAD_SCHEME
import dev.nittala.voicegateway.core.config.BaseUrlRefusal.CLEARTEXT
import dev.nittala.voicegateway.core.config.BaseUrlRefusal.EMPTY_OR_TOO_LONG
import dev.nittala.voicegateway.core.config.BaseUrlRefusal.FRAGMENT
import dev.nittala.voicegateway.core.config.BaseUrlRefusal.HOST_NOT_ALLOWED
import dev.nittala.voicegateway.core.config.BaseUrlRefusal.NOT_ASCII
import dev.nittala.voicegateway.core.config.BaseUrlRefusal.PATH
import dev.nittala.voicegateway.core.config.BaseUrlRefusal.QUERY
import dev.nittala.voicegateway.core.config.BaseUrlRefusal.USERINFO
import dev.nittala.voicegateway.core.config.BaseUrlRefusal.WHITESPACE
import dev.nittala.voicegateway.core.config.BuildVariant.DEBUG
import dev.nittala.voicegateway.core.config.BuildVariant.RELEASE
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec (A7): table tests for [validateBaseUrl]. Each row: input, then the answer in release and in debug. */
class BaseUrlTest {
    private fun ok(url: String) = BaseUrlResult.Valid(url)
    private fun no(reason: BaseUrlRefusal) = BaseUrlResult.Refused(reason)

    private fun check(rows: List<Triple<String, BaseUrlResult, BaseUrlResult>>) {
        val failures = rows.mapNotNull { (raw, release, debug) ->
            val gotRelease = validateBaseUrl(raw, RELEASE)
            val gotDebug = validateBaseUrl(raw, DEBUG)
            if (gotRelease == release && gotDebug == debug) null
            else "${raw.escaped()}: release $gotRelease (want $release), debug $gotDebug (want $debug)"
        }
        assertEquals("", failures.joinToString("\n"))
    }

    private fun String.escaped() = map { if (it.code in 0x21..0x7e) "$it" else "\\u%04x".format(it.code) }.joinToString("")

    /** Same answer in both builds. */
    private fun both(raw: String, answer: BaseUrlResult) = Triple(raw, answer, answer)

    @Test
    fun acceptsTsNetHttpsInBothBuilds() = check(
        listOf(
            both("https://gateway.example.ts.net", ok("https://gateway.example.ts.net")),
            both("https://gateway.example.ts.net/", ok("https://gateway.example.ts.net")),
            both("HTTPS://Gateway.Example.TS.NET/", ok("https://gateway.example.ts.net")),
            both("https://gateway.example.ts.net:443", ok("https://gateway.example.ts.net")),
            both("https://gateway.example.ts.net:8443/", ok("https://gateway.example.ts.net:8443")),
            both("https://a.ts.net", ok("https://a.ts.net")),
            both("https://my-mac.tail-1234.ts.net", ok("https://my-mac.tail-1234.ts.net")),
            both("https://0.example.ts.net", ok("https://0.example.ts.net")),
        ),
    )

    @Test
    fun refusesHostsThatOnlyLookLikeTsNet() = check(
        listOf(
            both("https://a.ts.net.evil.com", no(HOST_NOT_ALLOWED)),
            both("https://evil.com/.ts.net", no(HOST_NOT_ALLOWED)),
            both("https://evil.com?.ts.net", no(HOST_NOT_ALLOWED)),
            both("https://evil.com#.ts.net", no(HOST_NOT_ALLOWED)),
            both("https://evilts.net", no(HOST_NOT_ALLOWED)),
            both("https://ts.net", no(HOST_NOT_ALLOWED)),
            both("https://example.com", no(HOST_NOT_ALLOWED)),
            both("https://gateway.example.ts.net.", no(BAD_HOST)),
            both("https://gateway.example.ts.net./", no(BAD_HOST)),
            both("https://.ts.net", no(BAD_HOST)),
            both("https://a..ts.net", no(BAD_HOST)),
            both("https://-a.ts.net", no(BAD_HOST)),
            both("https://a-.ts.net", no(BAD_HOST)),
            both("https://a_b.ts.net", no(BAD_HOST)),
            both("https://a*.ts.net", no(BAD_HOST)),
            both("https://xn--gtwy-ppa.ts.net", no(BAD_HOST)),
            both("https://${"a".repeat(64)}.ts.net", no(BAD_HOST)),
            both("https://" + List(43) { "abcde" }.joinToString(".") + ".ts.net", no(BAD_HOST)),
            both("https://", no(BAD_HOST)),
            both("https://:443", no(BAD_HOST)),
            both("https://[::1]", no(BAD_HOST)),
            both("https://[fd7a:115c:a1e0::1]:443", no(BAD_HOST)),
            both("https://100.64.0.1", no(HOST_NOT_ALLOWED)),
        ),
    )

    @Test
    fun refusesUnicodeLookalikes() = check(
        listOf(
            both("https://gateway.example.ts.nеt", no(NOT_ASCII)), // Cyrillic e
            both("https://gateway.example.тs.net", no(NOT_ASCII)), // Cyrillic te
            both("https://gateway.example.ts。net", no(NOT_ASCII)), // ideographic full stop
            both("https://gateway.example.ts．net", no(NOT_ASCII)), // fullwidth full stop
            both("https://gateway.example.ts.net∕evil.com", no(NOT_ASCII)), // division slash
            both("https://gateway​.example.ts.net", no(NOT_ASCII)), // zero-width space
            both("ｈttps://gateway.example.ts.net", no(NOT_ASCII)), // fullwidth h
        ),
    )

    @Test
    fun refusesUserinfoPathQueryFragment() = check(
        listOf(
            both("https://user:pw@gateway.example.ts.net", no(USERINFO)),
            both("https://user@gateway.example.ts.net", no(USERINFO)),
            both("https://@gateway.example.ts.net", no(USERINFO)),
            both("https://gateway.example.ts.net@evil.com", no(USERINFO)),
            both("https://gateway.example.ts.net/@evil.com", no(PATH)),
            both("https://gateway.example.ts.net/api", no(PATH)),
            both("https://gateway.example.ts.net//", no(PATH)),
            both("https://gateway.example.ts.net/..", no(PATH)),
            both("https://gateway.example.ts.net?", no(QUERY)),
            both("https://gateway.example.ts.net/?a=1", no(QUERY)),
            both("https://gateway.example.ts.net#", no(FRAGMENT)),
            both("https://gateway.example.ts.net/#top", no(FRAGMENT)),
        ),
    )

    @Test
    fun refusesWhitespaceBackslashAndControlCharacters() = check(
        listOf(
            both("", no(EMPTY_OR_TOO_LONG)),
            both("https://gateway.example.ts.net/" + "a".repeat(MAX_BASE_URL_LENGTH), no(EMPTY_OR_TOO_LONG)),
            both(" https://gateway.example.ts.net", no(WHITESPACE)),
            both("https://gateway.example.ts.net ", no(WHITESPACE)),
            both("https://gateway.example.ts.net\n", no(WHITESPACE)),
            both("https://gateway.example .ts.net", no(WHITESPACE)),
            both("https://gateway.example.ts.net\t", no(WHITESPACE)),
            both("https://gateway.example.ts.net\u0000", no(WHITESPACE)),
            both("https://gateway.example.ts.net\u007F", no(WHITESPACE)),
            both("https://gateway.example.ts.net ", no(WHITESPACE)),
            both("https:\\\\gateway.example.ts.net", no(BACKSLASH)),
            both("https://evil.com\\.gateway.example.ts.net", no(BACKSLASH)),
            both("https://evil.com\\@gateway.example.ts.net", no(BACKSLASH)),
        ),
    )

    @Test
    fun refusesOtherSchemes() = check(
        listOf(
            both("gateway.example.ts.net", no(BAD_SCHEME)),
            both("//gateway.example.ts.net", no(BAD_SCHEME)),
            both("ftp://gateway.example.ts.net", no(BAD_SCHEME)),
            both("ws://gateway.example.ts.net", no(BAD_SCHEME)),
            both("wss://gateway.example.ts.net", no(BAD_SCHEME)),
            both("javascript:alert(1)", no(BAD_SCHEME)),
            both("file:///etc/hosts", no(BAD_SCHEME)),
            both("https:gateway.example.ts.net", no(BAD_SCHEME)),
            both("https:/gateway.example.ts.net", no(BAD_SCHEME)),
            both("httpss://gateway.example.ts.net", no(BAD_SCHEME)),
        ),
    )

    @Test
    fun checksPorts() = check(
        listOf(
            both("https://gateway.example.ts.net:1", ok("https://gateway.example.ts.net:1")),
            both("https://gateway.example.ts.net:65535", ok("https://gateway.example.ts.net:65535")),
            both("https://gateway.example.ts.net:0443", ok("https://gateway.example.ts.net")),
            both("https://gateway.example.ts.net:", no(BAD_PORT)),
            both("https://gateway.example.ts.net:0", no(BAD_PORT)),
            both("https://gateway.example.ts.net:65536", no(BAD_PORT)),
            both("https://gateway.example.ts.net:99999999999", no(BAD_PORT)),
            both("https://gateway.example.ts.net:-1", no(BAD_PORT)),
            both("https://gateway.example.ts.net:+443", no(BAD_PORT)),
            both("https://gateway.example.ts.net:44a", no(BAD_PORT)),
            both("https://gateway.example.ts.net:443:443", no(BAD_PORT)),
            both("https://gateway.example.ts.net:1/x", no(PATH)),
        ),
    )

    @Test
    fun cleartextOnlyInDebugAndOnlyToTailnetIpv4OrTheEmulatorHost() = check(
        listOf(
            Triple("http://100.64.0.1", no(CLEARTEXT), ok("http://100.64.0.1")),
            Triple("http://100.64.0.0:8766/", no(CLEARTEXT), ok("http://100.64.0.0:8766")),
            Triple("http://100.127.255.255:8766", no(CLEARTEXT), ok("http://100.127.255.255:8766")),
            Triple("http://100.101.102.103:80", no(CLEARTEXT), ok("http://100.101.102.103")),
            Triple("HTTP://10.0.2.2:8766", no(CLEARTEXT), ok("http://10.0.2.2:8766")),
            both("http://100.63.255.255", no(CLEARTEXT)),
            both("http://100.128.0.1", no(CLEARTEXT)),
            both("http://127.0.0.1:8766", no(CLEARTEXT)),
            both("http://localhost:8766", no(CLEARTEXT)),
            both("http://10.0.2.3", no(CLEARTEXT)),
            both("http://192.168.1.10", no(CLEARTEXT)),
            both("http://gateway.example.ts.net", no(CLEARTEXT)),
            both("http://100.064.0.1", no(CLEARTEXT)),
            both("http://100.64.0", no(CLEARTEXT)),
            both("http://100.64.0.1.2", no(CLEARTEXT)),
            both("http://100.64.0.256", no(CLEARTEXT)),
            both("http://1684013057", no(CLEARTEXT)), // 100.64.0.1 as one integer
            both("http://0x64.0x40.0.1", no(CLEARTEXT)),
            both("http://100.64.0.1.evil.com", no(CLEARTEXT)),
            both("http://10.0.2.2.evil.com", no(CLEARTEXT)),
            both("http://evil.com/100.64.0.1", no(CLEARTEXT)),
            both("http://user@100.64.0.1", no(CLEARTEXT)),
            both("http://[::1]", no(CLEARTEXT)),
            Triple("http://100.64.0.1/x", no(CLEARTEXT), no(PATH)),
            Triple("http://100.64.0.1:", no(CLEARTEXT), no(BAD_PORT)),
            Triple("http://10.0.2.2?x", no(CLEARTEXT), no(QUERY)),
        ),
    )

    @Test
    fun validUrlEqualsItsOrigin() {
        listOf("https://gateway.example.ts.net:8443/", "HTTPS://Gateway.Example.TS.NET", "http://100.64.0.1:8766").forEach {
            val valid = validateBaseUrl(it, DEBUG) as BaseUrlResult.Valid
            assertEquals(originOf(valid.url.toHttpUrl()), valid.url)
        }
    }
}
