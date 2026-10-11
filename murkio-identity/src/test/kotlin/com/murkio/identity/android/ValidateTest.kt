package com.murkio.identity.android

import com.murkio.identity.android.canvas.canSignIn
import com.murkio.identity.android.canvas.defaultCanvas
import com.murkio.identity.android.canvas.isSafeHref
import com.murkio.identity.android.canvas.sanitizeDocument
import com.murkio.identity.android.canvas.sanitizeParsed
import com.murkio.identity.android.internal.Json
import com.murkio.identity.android.internal.JsonException
import com.murkio.identity.android.internal.list
import com.murkio.identity.android.internal.obj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ValidateTest {
    private fun fixtures(kind: String): List<File> =
        File(specDir, "canvas/fixtures/$kind").listFiles { file -> file.name.endsWith(".json") }!!.sortedBy { it.name }

    private fun documentOf(file: File): Any? = Json.parse(file.readText()).obj()!!["document"]

    // The SDK skips what the server would reject but cannot hurt the app (an
    // unknown field or prop): it never reads them, so the document still draws.
    private val ignoredByTheSdk = setOf("unknown-field.json", "unknown-prop.json")

    @Test
    fun acceptsTheDefaultCanvasUnchanged() {
        val result = sanitizeParsed(readSpec("canvas/default.json"))
        assertEquals(emptyList<String>(), result.problems)
        assertTrue(canSignIn(result.document!!.signIn))
        assertEquals(9, result.document!!.signIn.elements.size)
        assertEquals(result.document!!.signIn.elements.map { it.id }, defaultCanvas().signIn.elements.map { it.id })
    }

    @Test
    fun validFixturesHaveNoStructuralProblem() {
        for (kind in listOf("valid", "unpublishable")) {
            val files = fixtures(kind)
            assertTrue(files.isNotEmpty())
            for (file in files) {
                val result = sanitizeParsed(documentOf(file))
                assertEquals("$kind/${file.name}", emptyList<String>(), result.problems)
                assertNotNull("$kind/${file.name}", result.document)
            }
        }
    }

    @Test
    fun invalidFixturesAreCaught() {
        val files = fixtures("invalid")
        assertTrue(files.size > 10)
        for (file in files) {
            val result = sanitizeParsed(documentOf(file))
            if (file.name in ignoredByTheSdk) {
                assertNotNull(file.name, result.document)
                continue
            }
            assertTrue(file.name, result.document == null || result.problems.isNotEmpty())
        }
    }

    @Test
    fun linksOnlyGoToHttpsOrASitePath() {
        assertTrue(isSafeHref("https://example.com/terms"))
        assertTrue(isSafeHref("https://example.com:8443/terms?x=1#top"))
        assertTrue(isSafeHref("/terms"))
        for (bad in listOf<Any?>(
            "//evil.example", "/\\evil.example", "javascript:alert(1)", "http://example.com", "https://user:pw@example.com",
            "https://exa mple.com", "myapp://callback", "https://", "https:///path", "https://:443/", 42, null,
        )) {
            assertFalse("$bad", isSafeHref(bad))
        }
    }

    private fun base(): MutableMap<String, Any?> = deepCopy(readSpec("canvas/default.json")).obj()!!.toMutableMap()

    @Suppress("UNCHECKED_CAST")
    private fun deepCopy(value: Any?): Any? = when (value) {
        is Map<*, *> -> (value as Map<String, Any?>).mapValuesTo(LinkedHashMap()) { deepCopy(it.value) }
        is List<*> -> value.mapTo(ArrayList()) { deepCopy(it) }
        else -> value
    }

    @Suppress("UNCHECKED_CAST")
    private fun element(document: Map<String, Any?>, index: Int): MutableMap<String, Any?> =
        document["screens"].obj()!!["sign_in"].obj()!!["elements"].list()!![index] as MutableMap<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun props(document: Map<String, Any?>, index: Int): MutableMap<String, Any?> =
        element(document, index)["props"] as MutableMap<String, Any?>

    @Test
    fun aTextWithBidiOrControlCharactersDropsItsElement() {
        val bidi = String(Character.toChars(0x202E))
        val isolate = String(Character.toChars(0x2066))
        val bell = String(Character.toChars(7))
        for (bad in listOf("Sign${bidi}in", "a${bell}b", "x${isolate}y", "   ")) {
            val document = base()
            props(document, 0)["text"] = mapOf("default" to bad)
            val result = sanitizeParsed(document)
            assertFalse(result.document!!.signIn.elements.any { it.id == "title" })
            assertTrue(result.problems.isNotEmpty())
        }
    }

    @Test
    fun onlyTheTextElementKeepsLineBreaks() {
        val document = base()
        props(document, 0)["text"] = mapOf("default" to "two\nlines")
        assertEquals(emptyList<String>(), sanitizeParsed(document).problems)

        val other = base()
        props(other, 6)["label"] = mapOf("default" to "two\nlines")
        assertTrue(sanitizeParsed(other).problems.isNotEmpty())
    }

    @Test
    fun aStyleOutOfRangeIsIgnoredAndTheElementStays() {
        val document = base()
        element(document, 6)["style"] = mapOf(
            "font_size" to 500.0, "opacity" to 3.0, "color" to "url(javascript:1)", "radius" to 12.0, "position" to "fixed",
        )
        val result = sanitizeParsed(document)
        val login = result.document!!.signIn.elements.first { it.id == "login" }
        assertNull(login.style.fontSize)
        assertNull(login.style.opacity)
        assertNull(login.style.color)
        assertEquals(12, login.style.radius)
        assertEquals(4, result.problems.size)
    }

    @Test
    fun numbersWrittenAs8Point0CountAsIntegers() {
        val result = sanitizeDocument(
            Json.write(readSpec("canvas/default.json")).replace("\"radius\":8", "\"radius\":8.0"),
        )
        assertEquals(8, result.document!!.theme.radius)

        val document = base()
        element(document, 6)["style"] = mapOf("radius" to 8.0, "padding" to 8.5)
        val login = sanitizeParsed(document).document!!.signIn.elements.first { it.id == "login" }
        assertEquals(8, login.style.radius)
        assertNull(login.style.padding)
    }

    @Test
    fun aDocumentThatIsNotASchema1CanvasIsRefused() {
        for (bad in listOf<Any?>(null, "x", 3.0, emptyList<Any?>(), emptyMap<String, Any?>(), mapOf("schema" to 2.0))) {
            assertNull(sanitizeParsed(bad).document)
        }
        assertNull(sanitizeDocument("{not json").document)
        assertNull(sanitizeDocument("").document)
    }

    @Test
    fun theJsonReaderIsStrictAndRoundTrips() {
        val text = """{"a":[1,2.5,-3e2,true,false,null],"b":{"c":"line\nbreak é \"quoted\" \\ /"},"d":""}"""
        val value = Json.parse(text).obj()!!
        assertEquals(listOf(1.0, 2.5, -300.0, true, false, null), value["a"])
        assertEquals("line\nbreak é \"quoted\" \\ /", value["b"].obj()!!["c"])
        assertEquals(value, Json.parse(Json.write(value)))
        assertEquals("""{"n":3,"f":0.5,"big":1.0E20}""", Json.write(linkedMapOf("n" to 3.0, "f" to 0.5, "big" to 1e20)))

        for (bad in listOf("", "{", "[1,]", "{\"a\":1,}", "01", "1.", "nul", "\"unterminated", "{\"a\" 1}", "[1] x", "'single'", "\"tab\there\"")) {
            assertThrows(bad, JsonException::class.java) { Json.parse(bad) }
        }
        assertThrows(JsonException::class.java) { Json.parse("[".repeat(100) + "]".repeat(100)) }
        assertNull(Json.parseOrNull(null))
    }
}
