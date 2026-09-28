package com.suyaphot.app.folders

import com.suyaphot.app.domain.folders.FolderManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FolderManagerTest {

    private lateinit var fakeFolderDao: FakeFolderDao
    private lateinit var folderManager: FolderManager

    @Before
    fun setUp() {
        fakeFolderDao = FakeFolderDao()
        folderManager = FolderManager(
            folderDao = fakeFolderDao
        )
    }

    @Test
    fun testNormalizeFolderNameValid() {
        assertEquals("Vacation 2026", FolderManager.normalizeFolderName("  Vacation 2026  "))
        assertEquals("A", FolderManager.normalizeFolderName("A"))
    }

    @Test
    fun testNormalizeFolderNameRejectsBlank() {
        assertThrows(IllegalArgumentException::class.java) {
            FolderManager.normalizeFolderName("   ")
        }
    }

    @Test
    fun testNormalizeFolderNameRejectsTooLong() {
        val longName = "A".repeat(121)
        assertThrows(IllegalArgumentException::class.java) {
            FolderManager.normalizeFolderName(longName)
        }
    }

    @Test
    fun testNormalizeFolderNameRejectsControlChars() {
        assertThrows(IllegalArgumentException::class.java) {
            FolderManager.normalizeFolderName("Folder\u0000Name")
        }
    }

    @Test
    fun testCanMoveFolderDirectSelfCheck() = runBlocking {
        assertFalse(folderManager.canMoveFolder("folderA", "folderA"))
    }

    @Test
    fun testCanMoveFolderToRoot() = runBlocking {
        fakeFolderDao.setParent("folderB", "folderA")
        assertTrue(folderManager.canMoveFolder("folderB", null))
    }

    @Test
    fun testCanMoveFolderPreventsCycles() = runBlocking {
        // Hierarchy: Root -> A -> B -> C -> D
        fakeFolderDao.setParent("A", null)
        fakeFolderDao.setParent("B", "A")
        fakeFolderDao.setParent("C", "B")
        fakeFolderDao.setParent("D", "C")

        // Moving A into its descendants must fail
        assertFalse(folderManager.canMoveFolder("A", "D"))
        assertFalse(folderManager.canMoveFolder("A", "C"))
        assertFalse(folderManager.canMoveFolder("A", "B"))

        // Moving D into A is valid
        assertTrue(folderManager.canMoveFolder("D", "A"))
        // Moving C into Root is valid
        assertTrue(folderManager.canMoveFolder("C", null))
    }
}
