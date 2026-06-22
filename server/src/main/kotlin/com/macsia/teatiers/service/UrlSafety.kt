package com.macsia.teatiers.service

import inet.ipaddr.HostName
import inet.ipaddr.IPAddress
import inet.ipaddr.IPAddressString
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URISyntaxException

/**
 * SSRF / host-allowlist gate for every URL the importer persists or a run fetches (decision #141, PR-2;
 * refresh ING-P0-2). A URL must be https, credential-free, fragment-free, on the default port, on an
 * explicit-allowlist host, and must NOT be a private/loopback/link-local/reserved IP literal.
 *
 * The allowlist (curated bare hostnames per source) is the PRIMARY gate. The IP-literal classification is
 * defense-in-depth for an operator who mistakenly allowlists an address: it uses seancfoley/IPAddress with
 * its default (permissive) parser deliberately, so an obfuscated internal address (octal `0177.0.0.1`,
 * inet_aton `2130706433`, IPv4-mapped `::ffff:127.0.0.1`) is still normalized to its real value and
 * rejected -- rather than slipping through as an "ordinary hostname".
 *
 * DNS-rebinding (a hostname that resolves to an internal IP) is NOT handled here: the server does not
 * resolve. The fetcher (the future scraper sidecar) must re-resolve and re-check every resolved address
 * against this same classification immediately before connecting.
 */
@Component
class UrlSafety {

    /** Throws [UrlSafetyException] if [rawUrl] is unsafe or off the [allowedHosts] allowlist. */
    fun validate(rawUrl: String, allowedHosts: Set<String>) {
        val uri = try {
            URI(rawUrl)
        } catch (e: URISyntaxException) {
            throw UrlSafetyException("unparseable URL '$rawUrl': ${e.reason}")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https") throw UrlSafetyException("URL '$rawUrl' must be https, got scheme '$scheme'")
        if (uri.rawUserInfo != null) throw UrlSafetyException("URL '$rawUrl' must not carry credentials")
        if (uri.rawFragment != null) throw UrlSafetyException("URL '$rawUrl' must not carry a fragment")
        if (uri.port != -1 && uri.port != HTTPS_PORT) throw UrlSafetyException("URL '$rawUrl' uses a non-default port ${uri.port}")

        val host = uri.host?.lowercase()?.trim('[', ']')
            ?: throw UrlSafetyException("URL '$rawUrl' has no parseable host")
        if (host.isBlank() || host == "localhost" || host.endsWith(".localhost")) {
            throw UrlSafetyException("URL '$rawUrl' host '$host' is not permitted")
        }
        forbiddenIpLiteral(host)?.let { throw UrlSafetyException("URL '$rawUrl' host is a forbidden IP literal ($it)") }
        if (host !in allowedHosts) {
            throw UrlSafetyException("URL '$rawUrl' host '$host' is not in the source allowlist $allowedHosts")
        }
    }

    /** If [host] is an IP literal in a non-public range, a short reason; null if it is a DNS name or public. */
    private fun forbiddenIpLiteral(host: String): String? {
        val literal: IPAddress = HostName(host).asAddress() ?: return null // a DNS name, not an IP literal
        val ip = unwrapV4Mapped(literal)
        if (ip.isMultiple) return "subnet/range"
        if (ip.isLoopback) return "loopback"
        if (ip.isLinkLocal) return "link-local"
        if (ip.isMulticast) return "multicast"
        if (ip.isAnyLocal || ip.isUnspecified) return "unspecified"
        if (ip.isLocal) return "local"
        if (ip.isIPv6) {
            val v6 = ip.toIPv6()
            if (v6.isSiteLocal || v6.isUniqueLocal) return "ipv6-local"
        }
        FORBIDDEN_CIDRS.firstOrNull { it.contains(ip) }?.let { return "reserved ${it.toCanonicalString()}" }
        return null
    }

    /** Unwrap an IPv4-mapped IPv6 address (::ffff:127.0.0.1) to its IPv4 form so the v4 checks see the truth. */
    private fun unwrapV4Mapped(ip: IPAddress): IPAddress =
        if (ip.isIPv6 && ip.toIPv6().isIPv4Mapped) (ip.toIPv6().embeddedIPv4Address ?: ip) else ip

    private companion object {
        const val HTTPS_PORT = 443

        // Ranges IPAddress has no single predicate for: RFC1918, CGNAT, this-network, link-local,
        // IPv6 ULA/site-local, and documentation ranges.
        val FORBIDDEN_CIDRS: List<IPAddress> = listOf(
            "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10",
            "0.0.0.0/8", "169.254.0.0/16", "fc00::/7", "fec0::/10", "2001:db8::/32",
        ).map { IPAddressString(it).address }
    }
}

/** A scraped/observed URL failed the SSRF / host-allowlist gate (decision #141, PR-2). */
class UrlSafetyException(message: String) : RuntimeException(message)
