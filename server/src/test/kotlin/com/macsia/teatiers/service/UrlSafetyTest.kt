package com.macsia.teatiers.service

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Decision #141, PR-2: the SSRF / host-allowlist gate. The allowlist is the primary gate; IP-literal
 * classification (via seancfoley/IPAddress, default permissive parser) is defense-in-depth that normalizes
 * obfuscated internal addresses (octal / inet_aton / IPv4-mapped) before rejecting them. Pure unit test.
 */
class UrlSafetyTest {

    private val urlSafety = UrlSafety()
    private val allowed = setOf("artoftea.ru", "example.com")

    private fun reject(url: String) = assertFailsWith<UrlSafetyException>("expected rejection: $url") {
        urlSafety.validate(url, allowed)
    }

    @Test
    fun `an https url on an allowlisted host passes`() {
        urlSafety.validate("https://artoftea.ru/puer/da-hong-pao", allowed)
        urlSafety.validate("https://example.com:443/x?q=1", allowed) // explicit default port is fine
    }

    @Test
    fun `a non-https scheme is rejected`() {
        reject("http://artoftea.ru/x")
        reject("ftp://artoftea.ru/x")
        reject("file:///etc/passwd")
    }

    @Test
    fun `credentials, fragments and non-default ports are rejected`() {
        reject("https://user:pass@artoftea.ru/x") // userinfo
        reject("https://artoftea.ru/x#frag")       // fragment
        reject("https://artoftea.ru:8080/x")       // alternate port
    }

    @Test
    fun `a host off the allowlist is rejected (including a look-alike subdomain)`() {
        reject("https://evil.example/x")
        reject("https://artoftea.ru.evil.com/x")
        reject("https://notallowed.com/x")
    }

    @Test
    fun `forbidden IP literals are rejected even in obfuscated forms`() {
        reject("https://127.0.0.1/x")          // loopback
        reject("https://10.0.0.5/x")           // RFC1918
        reject("https://192.168.1.1/x")        // RFC1918
        reject("https://169.254.169.254/x")    // link-local / cloud metadata
        reject("https://0177.0.0.1/x")         // octal-obfuscated loopback
        reject("https://2130706433/x")         // inet_aton-obfuscated loopback
        reject("https://[::1]/x")              // IPv6 loopback
        reject("https://[::ffff:127.0.0.1]/x") // IPv4-mapped loopback
        reject("https://localhost/x")          // localhost name
    }

    @Test
    fun `a blank or unparseable url is rejected`() {
        reject("")
        reject("not a url")
        reject("https://")
    }
}
