package com.vik.immishare.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareLinkTest {
    @Test
    fun plainHttpsLink() {
        assertEquals(ShareLink("https://photos.example.com", "AbC-12_x"), ShareLink.find("https://photos.example.com/share/AbC-12_x"))
    }

    @Test
    fun keepsPortAndCleartext() {
        assertEquals(ShareLink("http://192.168.1.20:2283", "key1"), ShareLink.find("http://192.168.1.20:2283/share/key1"))
    }

    @Test
    fun keepsReverseProxySubpath() {
        assertEquals(ShareLink("https://home.example.org/immich", "k"), ShareLink.find("https://home.example.org/immich/share/k/"))
    }

    @Test
    fun extractsFirstLinkFromSurroundingText() {
        val text = "Look at these! https://example.com/about and https://Img.Example.com/share/first, also https://x.com/share/second"
        assertEquals(ShareLink("https://img.example.com", "first"), ShareLink.find(text))
    }

    @Test
    fun ignoresQueryAndFragment() {
        assertEquals(ShareLink("https://a.b", "key"), ShareLink.find("https://a.b/share/key?foo=1#bar"))
    }

    @Test
    fun ipv6Host() {
        assertEquals(ShareLink("http://[fd00::1]:2283", "key"), ShareLink.find("http://[fd00::1]:2283/share/key"))
    }

    @Test
    fun rejectsNonShareLinks() {
        assertNull(ShareLink.find("https://example.com/photos/abc"))
        assertNull(ShareLink.find("ftp://example.com/share/abc"))
        assertNull(ShareLink.find("no link here"))
        assertNull(ShareLink.find(null))
        assertNull(ShareLink.find("https://example.com/share/"))
    }

    @Test
    fun detectsLocalHosts() {
        assertTrue(ShareLink("http://192.168.1.5:2283", "k").looksLocal())
        assertTrue(ShareLink("http://nas.local", "k").looksLocal())
        assertTrue(ShareLink("http://immich", "k").looksLocal())
        assertTrue(ShareLink("http://[fd00::1]", "k").looksLocal())
        assertFalse(ShareLink("https://photos.example.com", "k").looksLocal())
        assertFalse(ShareLink("http://8.8.8.8", "k").looksLocal())
    }

    @Test
    fun buildsUrlBack() {
        assertEquals("https://h.io/sub/share/K", ShareLink("https://h.io/sub", "K").url)
    }
}
