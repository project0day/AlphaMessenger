package com.alphamessenger.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.URL

class PublicHttpTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test
    fun privateAndReservedAddressesAreNotPublic() {
        for (a in listOf(
            "127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.1.1", "0.0.0.0",
            "100.64.0.1", "198.18.0.1", "192.0.0.8", "240.0.0.1", "224.0.0.1",
            "::1", "fe80::1", "fc00::1", "fd12:3456::1", "::",
        )) assertFalse(a, PublicHttp.isPublic(ip(a)))
        for (a in listOf("1.1.1.1", "8.8.8.8", "2606:4700:4700::1111")) assertTrue(a, PublicHttp.isPublic(ip(a)))
    }

    @Test
    fun namesAndSchemesOutsideThePublicWebAreRefused() {
        for (u in listOf(
            "http://example.com/", "https://user:pw@example.com/", "https://localhost/", "https://printer.local/",
            "https://nas.lan/", "https://box.home.arpa/", "https://127.0.0.1/", "https://192.168.0.1/",
            "https://[::1]/", "https://[fe80::1]/",
        )) {
            try { PublicHttp.requirePublicName(URL(u)); fail("accepted $u") } catch (_: java.io.IOException) {}
        }
        PublicHttp.requirePublicName(URL("https://example.com/"))
    }

    @Test
    fun aNameAnsweringAnyPrivateAddressIsRefused() {
        // A rebinding name answers a public and a private address at once, or
        // only a private one: either way no connection is made.
        for (answer in listOf(listOf(ip("93.184.216.34"), ip("192.168.1.10")), listOf(ip("10.0.0.5")), emptyList())) {
            try {
                PublicHttp.PublicDns { answer }.lookup("rebind.example")
                fail("accepted $answer")
            } catch (_: java.net.UnknownHostException) {}
        }
        val ok = PublicHttp.PublicDns { listOf(ip("93.184.216.34")) }.lookup("example.com")
        assertTrue(ok.size == 1)
    }
}
