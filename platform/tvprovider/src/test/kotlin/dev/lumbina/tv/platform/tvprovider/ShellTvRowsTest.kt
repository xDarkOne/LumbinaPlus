package dev.lumbina.tv.platform.tvprovider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShellTvRowsTest {
    @Test
    fun `preserves unicode, escaped newlines, numeric ids and nulls`() {
        val rows = parseShellRows(
            "warning\nTVPROVIDER_JSON=channel\t[{\"_id\":9223372036854775806," +
                "\"title\":\"Любовь\\nи голуби\",\"poster_art_uri\":null}]\nTVPROVIDER_JSON=preview_program\t[]",
            "channel",
        )!!
        assertEquals(9223372036854775806L, rows.single().long("_id"))
        assertEquals("Любовь\nи голуби", rows.single().string("title"))
        assertNull(rows.single().string("poster_art_uri"))
    }

    @Test
    fun `distinguishes empty results from failed or malformed output`() {
        assertEquals(emptyList<TvRow>(), parseShellRows("TVPROVIDER_JSON=channel\t[]", "channel"))
        assertNull(parseShellRows(null, "channel"))
        assertNull(parseShellRows("TVPROVIDER_JSON=channel\t[not-json]", "channel"))
    }
}
