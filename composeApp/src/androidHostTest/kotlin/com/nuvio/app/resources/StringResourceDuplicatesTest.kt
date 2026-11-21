package com.nuvio.app.resources

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A string defined in two resource files of one language compiles, but the app crashes the moment the
 * string is read ("Resource with ID=... has more than one file"). This catches it at build time, as
 * happens when upstream and the fork add the same string in different files.
 */
class StringResourceDuplicatesTest {

    @Test
    fun noStringIsDefinedInTwoFilesOfOneLanguage() {
        val root = listOf(
            File("src/commonMain/composeResources"),
            File("composeApp/src/commonMain/composeResources"),
        ).first { it.isDirectory }
        val pattern = Regex("""<(string|plurals|string-array)\s+name="([^"]+)"""")

        val duplicates = root.listFiles { file -> file.isDirectory && file.name.startsWith("values") }.orEmpty()
            .sortedBy { it.name }
            .flatMap { language ->
                val definedIn = mutableMapOf<Pair<String, String>, MutableList<String>>()
                language.listFiles { file -> file.extension == "xml" }.orEmpty().sortedBy { it.name }.forEach { file ->
                    pattern.findAll(file.readText()).forEach { match ->
                        definedIn.getOrPut(match.groupValues[1] to match.groupValues[2]) { mutableListOf() }.add(file.name)
                    }
                }
                definedIn.filterValues { it.size > 1 }.map { (key, files) -> "${language.name}: ${key.second} in $files" }
            }

        assertTrue(duplicates.isEmpty(), "Duplicate string resources:\n" + duplicates.joinToString("\n"))
    }
}
