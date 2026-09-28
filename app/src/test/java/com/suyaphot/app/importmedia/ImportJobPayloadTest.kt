package com.suyaphot.app.importmedia

import com.suyaphot.app.core.model.ImportMode
import com.suyaphot.app.domain.importmedia.ImportJobPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ImportJobPayloadTest {
    @Test
    fun roundTripPreservesMoveModeAndFields() {
        val original = ImportJobPayload(
            sourceUri = "content://media/external/images/media/42",
            targetFolderId = "folder_1",
            itemId = "item_1",
            mode = ImportMode.MOVE
        )

        assertEquals(original, ImportJobPayload.deserialize(original.serialize()))
    }

    @Test
    fun roundTripPreservesNullFolderAndCopyMode() {
        val original = ImportJobPayload(
            sourceUri = "content://picker/item",
            targetFolderId = null,
            itemId = "item_2",
            mode = ImportMode.COPY
        )

        assertEquals(original, ImportJobPayload.deserialize(original.serialize()))
    }

    @Test
    fun parserRejectsTruncatedAndOversizedPayloads() {
        val valid = ImportJobPayload(
            sourceUri = "content://picker/item",
            targetFolderId = null,
            itemId = "item_3",
            mode = ImportMode.COPY
        ).serialize()

        assertThrows(IllegalArgumentException::class.java) {
            ImportJobPayload.deserialize(valid.copyOf(valid.size - 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImportJobPayload.deserialize(ByteArray(64 * 1024 + 1))
        }
    }
}
