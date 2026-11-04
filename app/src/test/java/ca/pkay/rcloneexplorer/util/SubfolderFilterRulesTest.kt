package ca.pkay.rcloneexplorer.util

import ca.pkay.rcloneexplorer.Items.Filter
import ca.pkay.rcloneexplorer.Items.FilterEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubfolderFilterRulesTest {

    @Test
    fun buildRulesOrdersCaseInsensitivelyAndEndsWithExcludeAll() {
        val rules = SubfolderFilterRules.buildRules(listOf("(Life)", "(Hobbies)"))
        assertEquals(3, rules.size)
        assertEquals(FilterEntry.FILTER_INCLUDE, rules[0].filterType)
        assertEquals("/(Hobbies)/**", rules[0].filter)
        assertEquals(FilterEntry.FILTER_INCLUDE, rules[1].filterType)
        assertEquals("/(Life)/**", rules[1].filter)
        assertEquals(FilterEntry.FILTER_EXCLUDE, rules[2].filterType)
        assertEquals("**", rules[2].filter)
    }

    @Test
    fun escapeGlobAndUnescapeRoundTripSpecialCharacters() {
        val original = "[2024] a*b"
        assertEquals("\\[2024\\] a\\*b", SubfolderFilterRules.escapeGlob(original))
        assertEquals(original, SubfolderFilterRules.unescapeGlob(SubfolderFilterRules.escapeGlob(original)))

        val filter = Filter(1)
        filter.setFilters(SubfolderFilterRules.buildRules(listOf(original)))
        assertEquals(setOf(original), SubfolderFilterRules.parseSelectedFolders(filter))
    }

    @Test
    fun nestedRelativePathsRoundTripThroughBuildAndParse() {
        val selected = listOf("(Hobbies)/Cameras", "(Life)")
        val filter = Filter(1)
        filter.setFilters(SubfolderFilterRules.buildRules(selected))
        assertEquals(
            setOf("(Hobbies)/Cameras", "(Life)"),
            SubfolderFilterRules.parseSelectedFolders(filter),
        )
        assertEquals("/(Hobbies)/Cameras/**", filter.getFilters()[0].filter)
        assertEquals("/(Life)/**", filter.getFilters()[1].filter)
        assertEquals("**", filter.getFilters()[2].filter)
    }

    @Test
    fun pruneRedundantDropsDescendantsOfSelectedParents() {
        val rules = SubfolderFilterRules.buildRules(
            listOf("(Hobbies)", "(Hobbies)/Cameras", "(Life)"),
        )
        assertEquals(3, rules.size)
        assertEquals("/(Hobbies)/**", rules[0].filter)
        assertEquals("/(Life)/**", rules[1].filter)
        assertEquals("**", rules[2].filter)
    }

    @Test
    fun parseSelectedFoldersRejectsNonGeneratedShapes() {
        assertNull(SubfolderFilterRules.parseSelectedFolders(null))

        val handmade = Filter(1)
        handmade.setFilters(listOf(FilterEntry(FilterEntry.FILTER_INCLUDE, "*.jpg")))
        assertNull(SubfolderFilterRules.parseSelectedFolders(handmade))

        val missingExclude = Filter(2)
        missingExclude.setFilters(
            listOf(FilterEntry(FilterEntry.FILTER_INCLUDE, "/(Life)/**")),
        )
        assertNull(SubfolderFilterRules.parseSelectedFolders(missingExclude))

        val emptyName = Filter(4)
        emptyName.setFilters(
            listOf(
                FilterEntry(FilterEntry.FILTER_INCLUDE, "//**"),
                FilterEntry(FilterEntry.FILTER_EXCLUDE, "**"),
            ),
        )
        assertNull(SubfolderFilterRules.parseSelectedFolders(emptyName))
    }

    @Test
    fun buildRulesDropsDuplicatesAndBlanks() {
        val rules = SubfolderFilterRules.buildRules(listOf("A", " A ", ""))
        assertEquals(2, rules.size)
        assertEquals("/A/**", rules[0].filter)
        assertEquals("**", rules[1].filter)
    }

    @Test
    fun buildRulesEmptyInputReturnsEmptyList() {
        assertTrue(SubfolderFilterRules.buildRules(emptyList()).isEmpty())
    }

    @Test
    fun ancestorPathsListsParentsShortestFirst() {
        assertEquals(
            listOf("(Hobbies)", "(Hobbies)/Cameras"),
            SubfolderFilterRules.ancestorPaths("(Hobbies)/Cameras/2024"),
        )
        assertTrue(SubfolderFilterRules.ancestorPaths("(Life)").isEmpty())
    }

    @Test
    fun escapeRelativePathEscapesEachSegment() {
        assertEquals(
            "\\[2024\\]/a\\*b",
            SubfolderFilterRules.escapeRelativePath("[2024]/a*b"),
        )
    }
}
