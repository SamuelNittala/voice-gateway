package dev.nittala.voicegateway.core.config

/** Which build is asking: only [DEBUG] may use cleartext, and only to the hosts [validateBaseUrl] lists. */
enum class BuildVariant { RELEASE, DEBUG }

/** Why [validateBaseUrl] refused a URL. One reason per URL: the first rule it breaks, in the order listed. */
enum class BaseUrlRefusal {
    /** Empty, or longer than [MAX_BASE_URL_LENGTH]. */
    EMPTY_OR_TOO_LONG,

    /** Any whitespace or control character, anywhere (including leading or trailing). */
    WHITESPACE,

    /** A backslash anywhere: some parsers read it as `/`, which would move the host. */
    BACKSLASH,

    /** Any character outside printable ASCII (unicode lookalikes, IDN). */
    NOT_ASCII,

    /** Not `https://`, nor `http://` (scheme compared without case). */
    BAD_SCHEME,

    /** `http://` in a release build, or in a debug build to a host other than the debug cleartext hosts. */
    CLEARTEXT,

    /** `user[:password]@` before the host. */
    USERINFO,

    /** The host is empty, an IPv6 literal, has a trailing dot, or breaks DNS label rules (1-63 of `[a-z0-9-]`, no
     *  leading or trailing hyphen, 253 characters in all) or is a punycode (`xn--`) label. */
    BAD_HOST,

    /** A `:` with no port, a non-digit port, or a port outside 1..65535. */
    BAD_PORT,

    /** A well-formed host that is not allowed: https must be a `*.ts.net` name. */
    HOST_NOT_ALLOWED,

    /** Anything after the authority other than a single `/`. */
    PATH,

    /** A `?` after the authority. */
    QUERY,

    /** A `#` after the authority. */
    FRAGMENT,
}

/** The answer of [validateBaseUrl]. */
sealed interface BaseUrlResult {
    /** [url] is canonical: `<scheme>://<host>[:port]`, lowercase, no trailing slash, no default port. */
    data class Valid(val url: String) : BaseUrlResult

    data class Refused(val reason: BaseUrlRefusal) : BaseUrlResult
}

const val MAX_BASE_URL_LENGTH = 300

/**
 * Spec (A7), pure: is [raw] an acceptable gateway base URL for [variant]? Same spirit as gateway.py's
 * `valid_other_url`, but stricter, since this URL receives the passcode and the login cookie.
 *
 * Accepted in every build: `https://<host>[:port][/]` where host is a DNS name ending in `.ts.net` with at least
 * one label before `ts.net` (Tailscale Serve's certificate is from a public CA).
 *
 * Accepted only in [BuildVariant.DEBUG], additionally: `http://<host>[:port][/]` where host is a tailnet IPv4
 * address (100.64.0.0/10, dotted decimal with no leading zeros) or exactly `10.0.2.2` (the emulator's view of the
 * build machine). Nothing else gets cleartext, not even `127.0.0.1` or a ts.net name over http.
 *
 * Refused, with the [BaseUrlRefusal] of the first rule broken in its declared order: empty or too long; whitespace
 * or control characters (never trimmed); backslash; non-ASCII; other schemes; cleartext outside the rule above;
 * userinfo; malformed host (trailing dot, IPv6, label rules, punycode); malformed port; a host outside the allow
 * rules; a path, query or fragment.
 *
 * The scheme and host are compared without case; [BaseUrlResult.Valid.url] is the canonical lowercase form, which
 * is also the Origin the app sends (`originOf`).
 */
fun validateBaseUrl(raw: String, variant: BuildVariant): BaseUrlResult {
    characterRefusal(raw)?.let { return BaseUrlResult.Refused(it) }
    val schemeEnd = raw.indexOf("://")
    val scheme = if (schemeEnd < 0) "" else raw.substring(0, schemeEnd).lowercase()
    if (scheme != "https" && scheme != "http") return BaseUrlResult.Refused(BaseUrlRefusal.BAD_SCHEME)
    val rest = raw.substring(schemeEnd + 3)
    val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
    val authority = rest.substring(0, authorityEnd).lowercase()
    val tail = rest.substring(authorityEnd)
    if (scheme == "http" && !(variant == BuildVariant.DEBUG && isDebugCleartextHost(authority.substringBefore(':')))) {
        return BaseUrlResult.Refused(BaseUrlRefusal.CLEARTEXT)
    }
    val refusal = authorityRefusal(scheme, authority) ?: tailRefusal(tail)
    if (refusal != null) return BaseUrlResult.Refused(refusal)
    val host = authority.substringBefore(':')
    val port = authority.substringAfter(':', "").let { if (it.isEmpty()) defaultPort(scheme) else it.toInt() }
    return BaseUrlResult.Valid(if (port == defaultPort(scheme)) "$scheme://$host" else "$scheme://$host:$port")
}

private val DNS_LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
private val IPV4_PART = Regex("0|[1-9][0-9]{0,2}")
private const val EMULATOR_HOST = "10.0.2.2"

private fun defaultPort(scheme: String): Int = if (scheme == "https") 443 else 80

private fun characterRefusal(raw: String): BaseUrlRefusal? = when {
    raw.isEmpty() || raw.length > MAX_BASE_URL_LENGTH -> BaseUrlRefusal.EMPTY_OR_TOO_LONG
    raw.any { it.isWhitespace() || it.isISOControl() } -> BaseUrlRefusal.WHITESPACE
    '\\' in raw -> BaseUrlRefusal.BACKSLASH
    raw.any { it.code !in 0x21..0x7e } -> BaseUrlRefusal.NOT_ASCII
    else -> null
}

/** Userinfo, then host shape, then port, then the allow rule. [authority] is already lowercase. */
private fun authorityRefusal(scheme: String, authority: String): BaseUrlRefusal? {
    if ('@' in authority) return BaseUrlRefusal.USERINFO
    if ('[' in authority || ']' in authority) return BaseUrlRefusal.BAD_HOST
    val host = authority.substringBefore(':')
    if (!isDnsName(host)) return BaseUrlRefusal.BAD_HOST
    if (':' in authority && !isPort(authority.substringAfter(':'))) return BaseUrlRefusal.BAD_PORT
    val allowed = if (scheme == "https") isTsNetName(host) else isDebugCleartextHost(host)
    return if (allowed) null else BaseUrlRefusal.HOST_NOT_ALLOWED
}

/** Path first, then whichever of `?` and `#` comes first (a `?` inside a fragment is part of the fragment). */
private fun tailRefusal(tail: String): BaseUrlRefusal? {
    val path = tail.takeWhile { it != '?' && it != '#' }
    return when {
        path.isNotEmpty() && path != "/" -> BaseUrlRefusal.PATH
        tail.length == path.length -> null
        tail[path.length] == '?' -> BaseUrlRefusal.QUERY
        else -> BaseUrlRefusal.FRAGMENT
    }
}

private fun isDnsName(host: String): Boolean =
    host.length in 1..253 && host.split('.').all { DNS_LABEL.matches(it) && !it.startsWith("xn--") }

private fun isPort(text: String): Boolean =
    text.length in 1..5 && text.all { it in '0'..'9' } && text.toInt() in 1..65535

private fun isTsNetName(host: String): Boolean = host.endsWith(".ts.net") && host.count { it == '.' } >= 2

/** Exactly the emulator host, or dotted-decimal IPv4 (no leading zeros) inside 100.64.0.0/10. */
private fun isDebugCleartextHost(host: String): Boolean {
    if (host == EMULATOR_HOST) return true
    val parts = host.split('.')
    if (parts.size != 4 || !parts.all { IPV4_PART.matches(it) && it.toInt() <= 255 }) return false
    return parts[0] == "100" && parts[1].toInt() in 64..127
}
