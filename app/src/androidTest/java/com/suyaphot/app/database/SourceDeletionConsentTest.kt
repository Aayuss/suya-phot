package com.suyaphot.app.database

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suyaphot.app.domain.importmedia.SourceDeletionCoordinator
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceDeletionConsentTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val uri = Uri.parse("content://test/images/1")

    @Test fun api29ConsentRetriesActualDelete() {
        var calls = 0
        val coordinator = SourceDeletionCoordinator(context, deleteUri = { calls++; 1 }, probeAbsent = { false })
        val result = coordinator.completeConsent(listOf(uri), SourceDeletionCoordinator.DeleteConsentMode.API29_RETRY_REQUIRED)
        assertEquals(1, calls)
        assertEquals(listOf(uri), result.deletedUris)
        assertEquals(emptyList<Uri>(), result.retainedUris)
    }

    @Test fun api29ZeroRowsAndOriginalPresentStaysRetained() {
        var calls = 0
        val coordinator = SourceDeletionCoordinator(context, deleteUri = { calls++; 0 }, probeAbsent = { false })
        val result = coordinator.completeConsent(listOf(uri), SourceDeletionCoordinator.DeleteConsentMode.API29_RETRY_REQUIRED)
        assertEquals(1, calls)
        assertEquals(emptyList<Uri>(), result.deletedUris)
        assertEquals(listOf(uri), result.retainedUris)
    }

    @Test fun api30ConsentNeverRetriesDeleteAndRequiresAbsence() {
        var calls = 0
        val coordinator = SourceDeletionCoordinator(context, deleteUri = { calls++; 1 }, probeAbsent = { false })
        val result = coordinator.completeConsent(listOf(uri), SourceDeletionCoordinator.DeleteConsentMode.API30_SYSTEM_DELETE_REQUEST)
        assertEquals(0, calls)
        assertEquals(listOf(uri), result.retainedUris)
    }

    @Test fun api29MixedBatchKeepsFailedOriginalsAndContinues() {
        val second = Uri.parse("content://test/images/2")
        val third = Uri.parse("content://test/images/3")
        val calls = mutableListOf<Uri>()
        val coordinator = SourceDeletionCoordinator(context,
            deleteUri = { current ->
                calls += current
                if (current == second) throw SecurityException("still denied")
                if (current == third) 0 else 1
            },
            probeAbsent = { it == third }
        )
        val result = coordinator.completeConsent(listOf(uri, second, third), SourceDeletionCoordinator.DeleteConsentMode.API29_RETRY_REQUIRED)
        assertEquals(listOf(uri, second, third), calls)
        assertEquals(listOf(uri, third), result.deletedUris)
        assertEquals(listOf(second), result.retainedUris)
    }

    @Test fun directDeleteTreatsAlreadyAbsentSourceAsCompleted() {
        var calls = 0
        val coordinator = SourceDeletionCoordinator(
            context,
            deleteUri = { calls++; 0 },
            probeAbsent = { true }
        )
        val result = coordinator.deleteSources(listOf(uri))
        assertEquals(1, calls)
        val completed = result as SourceDeletionCoordinator.DeletionOutcome.CompletedDirectly
        assertEquals(listOf(uri), completed.deletedUris)
    }

}
