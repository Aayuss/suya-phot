package com.suyaphot.app.folders

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderAlgorithmTest {

    // Simple test double simulating FolderDao's getParentId
    private val parentMap = mutableMapOf<String, String?>()

    private fun canMoveFolder(folderId: String, newParentId: String?): Boolean {
        if (newParentId == null) return true
        if (folderId == newParentId) return false

        var cursor: String? = newParentId
        val visited = mutableSetOf<String>()

        while (cursor != null) {
            if (!visited.add(cursor)) return false // Loop guard
            if (cursor == folderId) return false
            cursor = parentMap[cursor]
        }
        return true
    }

    @Test
    fun testCannotMoveFolderIntoItself() {
        assertFalse(canMoveFolder("folderA", "folderA"))
    }

    @Test
    fun testCanMoveFolderToRoot() {
        parentMap["folderB"] = "folderA"
        assertTrue(canMoveFolder("folderB", null))
    }

    @Test
    fun testCannotMoveFolderIntoItsDescendant() {
        // Hierarchy: Root -> A -> B -> C -> D
        parentMap["A"] = null
        parentMap["B"] = "A"
        parentMap["C"] = "B"
        parentMap["D"] = "C"

        // Moving A into D would create a cycle: A -> ... -> D -> A
        assertFalse(canMoveFolder("A", "D"))
        assertFalse(canMoveFolder("A", "C"))
        assertFalse(canMoveFolder("A", "B"))

        // Moving D into A is valid
        assertTrue(canMoveFolder("D", "A"))
        // Moving C into Root is valid
        assertTrue(canMoveFolder("C", null))
    }
}
