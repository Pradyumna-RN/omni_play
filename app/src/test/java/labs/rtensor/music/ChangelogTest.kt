package labs.rtensor.music

import java.io.File
import labs.rtensor.music.ui.parseChangelog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogTest {
    @Test fun parsesReleasesSectionsAndWrappedItems() {
        val releases = parseChangelog("""
            # Changelog
            intro text

            ## 2.0 (versionCode 2)

            ### New
            - First
              continued here
            - Second

            ### Known
            - Gap

            ## 1.0
            ### Fixed
            - Old bug
        """.trimIndent())
        assertEquals(listOf("2.0 (versionCode 2)", "1.0"), releases.map { it.title })
        assertEquals(listOf("New", "Known"), releases[0].sections.map { it.title })
        assertEquals(listOf("First continued here", "Second"), releases[0].sections[0].items)
        assertEquals(listOf("Old bug"), releases[1].sections.single().items)
    }

    @Test fun repositoryChangelogHasTheCurrentReleaseFirst() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "CHANGELOG.md") }.first { it.exists() }
        val releases = parseChangelog(file.readText())
        assertTrue(releases.size >= 3)
        assertTrue(releases.first().title.startsWith(BuildConfig.VERSION_NAME))
        assertTrue(releases.all { r -> r.sections.isNotEmpty() && r.sections.all { it.items.isNotEmpty() } })
    }
}
