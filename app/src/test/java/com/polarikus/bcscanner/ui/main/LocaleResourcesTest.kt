package com.polarikus.bcscanner.ui.main

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.polarikus.bcscanner.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Unit tests for the OS-locale-driven UI language (FR-018).
 *
 * The interface language follows the system locale: `values-ru` is applied for a Russian
 * system locale, otherwise the default `values` (English) is used. There is no in-app
 * language switch and no settings screen.
 */
@RunWith(RobolectricTestRunner::class)
class LocaleResourcesTest {

    @Config(qualifiers = "ru")
    @Test
    fun russianLocaleUsesValuesRu() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals("Сканировать", context.getString(R.string.action_scan))
        assertEquals("Подключено", context.getString(R.string.connection_status_connected))
        assertEquals(
            "Наведите камеру на штрихкод или QR-код",
            context.getString(R.string.scanner_hint),
        )
    }

    @Config(qualifiers = "en")
    @Test
    fun englishLocaleFallsBackToDefaultValues() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals("Scan", context.getString(R.string.action_scan))
        assertEquals("Connected", context.getString(R.string.connection_status_connected))
    }

    @Config(qualifiers = "de")
    @Test
    fun anyNonRussianLocaleFallsBackToDefaultValues() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals("Scan", context.getString(R.string.action_scan))
        assertEquals("Connected", context.getString(R.string.connection_status_connected))
    }

    @Test
    fun onlyDefaultAndRussianValueDirectoriesAreDeclared() {
        val localeDirs = resolveResDir()
            .listFiles { file -> file.isDirectory && file.name.startsWith("values") }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()

        assertEquals(setOf("values", "values-ru"), localeDirs)
    }

    @Test
    fun stringKeysAreCompleteInBothLocales() {
        val resDir = resolveResDir()
        val defaultKeys = stringKeys(File(resDir, "values/strings.xml"))
        val russianKeys = stringKeys(File(resDir, "values-ru/strings.xml"))

        assertTrue("Default strings.xml must not be empty", defaultKeys.isNotEmpty())
        assertEquals(
            "values-ru must define exactly the same keys as values",
            defaultKeys,
            russianKeys,
        )
    }

    private fun resolveResDir(): File {
        val candidates = listOf(File("src/main/res"), File("app/src/main/res"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Cannot locate Android res dir; cwd=${File(".").absolutePath}")
    }

    private fun stringKeys(file: File): Set<String> {
        assertTrue("Missing resource file: ${file.absolutePath}", file.isFile)
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).mapNotNull { index ->
            (nodes.item(index) as? Element)?.getAttribute("name")
        }.toSet()
    }
}
