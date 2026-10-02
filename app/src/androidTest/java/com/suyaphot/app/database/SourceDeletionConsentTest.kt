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

    @Test fun api30ConsentRetainsUnknownPresence() {
        val coordinator = SourceDeletionCoordinator(
            context = context,
            deleteUri = { 0 },
            probeAbsent = null,
            probePresence = { SourceDeletionCoordinator.SourcePresence.UNKNOWN }
        )
        val result = coordinator.completeConsent(
            listOf(uri),
            SourceDeletionCoordinator.DeleteConsentMode.API30_SYSTEM_DELETE_REQUEST
        )
        assertEquals(emptyList<Uri>(), result.deletedUris)
        assertEquals(listOf(uri), result.retainedUris)
    }

    @Test fun api30ConsentDeletesAbsentPresence() {
        val coordinator = SourceDeletionCoordinator(
            context = context,
            deleteUri = { 0 },
            probeAbsent = null,
            probePresence = { SourceDeletionCoordinator.SourcePresence.ABSENT }
        )
        val result = coordinator.completeConsent(
            listOf(uri),
            SourceDeletionCoordinator.DeleteConsentMode.API30_SYSTEM_DELETE_REQUEST
        )
        assertEquals(listOf(uri), result.deletedUris)
        assertEquals(emptyList<Uri>(), result.retainedUris)
    }

    @Test fun api30MultiItemBatchDeleteBundlesAllItemsIntoSingleConsentRequest() {
        val uri1 = Uri.parse("content://media/external/images/media/101")
        val uri2 = Uri.parse("content://media/external/images/media/102")
        val uri3 = Uri.parse("content://media/external/images/media/103")

        val intent = android.content.Intent("TEST_ACTION")
        val pendingIntent = android.app.PendingIntent.getActivity(
            context, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val icon = android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.ic_delete)
        val action = android.app.RemoteAction(icon, "Delete", "Delete", pendingIntent)
        val rse = android.app.RecoverableSecurityException(SecurityException("Denied"), "Delete", action)

        val capturedBatchUris = mutableListOf<Uri>()
        val coordinator = SourceDeletionCoordinator(
            context = context,
            deleteUri = { throw rse },
            probeAbsent = null,
            probePresence = { SourceDeletionCoordinator.SourcePresence.PRESENT },
            createBatchDeleteRequest = { _, targets ->
                capturedBatchUris.addAll(targets)
                pendingIntent.intentSender
            }
        )

        val outcome = coordinator.deleteSources(listOf(uri1, uri2, uri3))
        org.junit.Assert.assertTrue(outcome is SourceDeletionCoordinator.DeletionOutcome.RequiresUserConsent)
        val consentOutcome = outcome as SourceDeletionCoordinator.DeletionOutcome.RequiresUserConsent

        assertEquals(SourceDeletionCoordinator.DeleteConsentMode.API30_SYSTEM_DELETE_REQUEST, consentOutcome.mode)
        assertEquals(listOf(uri1, uri2, uri3), consentOutcome.uris)
        assertEquals(listOf(uri1, uri2, uri3), capturedBatchUris)
        assertEquals(emptyList<Uri>(), consentOutcome.deletedUris)
    }

    @Test fun api30MultiItemWithMixedResultsDirectlyDeletesOwnedAndBatchesUnowned() {
        val ownedUri = Uri.parse("content://media/external/images/media/201")
        val unowned1 = Uri.parse("content://media/external/images/media/202")
        val unowned2 = Uri.parse("content://media/external/images/media/203")

        val intent = android.content.Intent("TEST_ACTION")
        val pendingIntent = android.app.PendingIntent.getActivity(
            context, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val icon = android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.ic_delete)
        val action = android.app.RemoteAction(icon, "Delete", "Delete", pendingIntent)
        val rse = android.app.RecoverableSecurityException(SecurityException("Denied"), "Delete", action)

        val capturedBatchUris = mutableListOf<Uri>()
        val coordinator = SourceDeletionCoordinator(
            context = context,
            deleteUri = { target ->
                if (target == ownedUri) 1 else throw rse
            },
            probeAbsent = null,
            probePresence = { SourceDeletionCoordinator.SourcePresence.PRESENT },
            createBatchDeleteRequest = { _, targets ->
                capturedBatchUris.addAll(targets)
                pendingIntent.intentSender
            }
        )

        val outcome = coordinator.deleteSources(listOf(ownedUri, unowned1, unowned2))
        org.junit.Assert.assertTrue(outcome is SourceDeletionCoordinator.DeletionOutcome.RequiresUserConsent)
        val consentOutcome = outcome as SourceDeletionCoordinator.DeletionOutcome.RequiresUserConsent

        assertEquals(SourceDeletionCoordinator.DeleteConsentMode.API30_SYSTEM_DELETE_REQUEST, consentOutcome.mode)
        assertEquals(listOf(unowned1, unowned2), consentOutcome.uris)
        assertEquals(listOf(unowned1, unowned2), capturedBatchUris)
        assertEquals(listOf(ownedUri), consentOutcome.deletedUris)
    }

    @Test fun api30DeduplicatesUris() {
        val uri1 = Uri.parse("content://media/external/images/media/301")
        val capturedBatchUris = mutableListOf<Uri>()

        val intent = android.content.Intent("TEST_ACTION")
        val pendingIntent = android.app.PendingIntent.getActivity(
            context, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val coordinator = SourceDeletionCoordinator(
            context = context,
            deleteUri = { 0 },
            probeAbsent = null,
            probePresence = { SourceDeletionCoordinator.SourcePresence.PRESENT },
            createBatchDeleteRequest = { _, targets ->
                capturedBatchUris.addAll(targets)
                pendingIntent.intentSender
            }
        )

        val outcome = coordinator.deleteSources(listOf(uri1, uri1, uri1))
        org.junit.Assert.assertTrue(outcome is SourceDeletionCoordinator.DeletionOutcome.RequiresUserConsent)
        val consentOutcome = outcome as SourceDeletionCoordinator.DeletionOutcome.RequiresUserConsent

        assertEquals(listOf(uri1), consentOutcome.uris)
        assertEquals(listOf(uri1), capturedBatchUris)
    }
}
