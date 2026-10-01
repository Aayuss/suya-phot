package com.suyaphot.app.feature.importmedia

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.suyaphot.app.app.SuyaApp
import com.suyaphot.app.domain.auth.VaultSession
import com.suyaphot.app.core.util.SafeLog
import com.suyaphot.app.domain.importmedia.SourceDeletionCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Transparent share target activity that directly saves shared media from external apps
 * (e.g. Google Photos, Samsung Gallery, File Manager) into the root folder of Suya Phot
 * without opening the full app UI, prompting for passwords/PINs, or asking for destination folders,
 * and requests deletion consent to remove the original from the public gallery (MOVE mode).
 */
class ShareReceiverActivity : ComponentActivity() {

    private var pendingConsentUris: List<Uri> = emptyList()
    private var pendingConsentMode: SourceDeletionCoordinator.DeleteConsentMode? = null

    private val deleteConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val app = application as SuyaApp
        val container = app.container
        container.sessionManager.endSystemActivity()
        val uris = pendingConsentUris
        val mode = pendingConsentMode
        pendingConsentUris = emptyList()
        pendingConsentMode = null

        lifecycleScope.launch {
            if (result.resultCode == Activity.RESULT_OK && mode != null && uris.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    container.sourceDeletionCoordinator.completeConsent(uris, mode)
                }
            }
            Toast.makeText(applicationContext, "Saved to Suya Phot", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        SafeLog.d("ShareReceiver", "onCreate: intent=$intent, action=${intent?.action}, type=${intent?.type}")

        val app = application as SuyaApp
        val container = app.container

        val sharedUris = extractUrisFromIntent(intent)
        SafeLog.d("ShareReceiver", "Extracted URIs: ${sharedUris.size}")
        if (sharedUris.isEmpty()) {
            SafeLog.w("ShareReceiver", "sharedUris is empty, finishing!")
            finish()
            return
        }

        lifecycleScope.launch {
            try {
                val hasVault = withContext(Dispatchers.IO) {
                    container.database.vaultDao().getAllVaults().isNotEmpty()
                }
                if (!hasVault) {
                    Toast.makeText(applicationContext, "Please set up Suya Phot first", Toast.LENGTH_LONG).show()
                    finish()
                    return@launch
                }

                val count = sharedUris.size
                val savingMsg = if (count == 1) "Saving to Suya Phot..." else "Saving $count items to Suya Phot..."
                Toast.makeText(applicationContext, savingMsg, Toast.LENGTH_SHORT).show()

                val stageResult = withContext(Dispatchers.IO) {
                    container.pendingShareManager.stageSharedMedia(sharedUris)
                }

                if (stageResult.stagedCount > 0) {
                    val session = container.sessionManager.sessionState.value
                    if (session is VaultSession.Unlocked) {
                        container.applicationScope.launch(Dispatchers.IO) {
                            container.pendingShareManager.processPendingShares(session)
                        }
                    }

                    // If we have deletion targets, request deletion consent to remove the originals from Gallery
                    if (stageResult.deletionTargets.isNotEmpty()) {
                        val outcome = withContext(Dispatchers.IO) {
                            container.sourceDeletionCoordinator.deleteSources(stageResult.deletionTargets)
                        }
                        when (outcome) {
                            is SourceDeletionCoordinator.DeletionOutcome.CompletedDirectly -> {
                                Toast.makeText(applicationContext, "Saved to Suya Phot", Toast.LENGTH_SHORT).show()
                                finish()
                            }
                            is SourceDeletionCoordinator.DeletionOutcome.RequiresUserConsent -> {
                                pendingConsentUris = outcome.uris
                                pendingConsentMode = outcome.mode
                                container.sessionManager.beginSystemActivity()
                                deleteConsentLauncher.launch(
                                    IntentSenderRequest.Builder(outcome.intentSender).build()
                                )
                            }
                            is SourceDeletionCoordinator.DeletionOutcome.Failed -> {
                                Toast.makeText(applicationContext, "Saved to Suya Phot", Toast.LENGTH_SHORT).show()
                                finish()
                            }
                        }
                    } else {
                        Toast.makeText(applicationContext, "Saved to Suya Phot", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                } else {
                    Toast.makeText(applicationContext, "Failed to save to Suya Phot", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Throwable) {
                SafeLog.e("ShareReceiver", "Exception in share receiver coroutine", e)
                finish()
            }
        }
    }

    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun extractUrisFromIntent(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return emptyList()
        val out = LinkedHashSet<Uri>()

        fun addIfValid(uri: Uri?) {
            if (uri == null || out.size >= 500) return
            if (uri.scheme != "content" && uri.scheme != "file") {
                return
            }
            val mime = runCatching { contentResolver.getType(uri) }.getOrNull()
                ?: intent.type
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    MimeTypeMap.getFileExtensionFromUrl(uri.toString())
                )
            if (mime == null || mime.startsWith("image/") || mime.startsWith("video/") || mime == "*/*") {
                out.add(uri)
            }
        }

        if (intent.action == Intent.ACTION_SEND) {
            val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            addIfValid(streamUri)

            intent.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) {
                    addIfValid(clip.getItemAt(i).uri)
                }
            }
        } else if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val streamUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            streamUris?.forEach(::addIfValid)

            intent.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) {
                    addIfValid(clip.getItemAt(i).uri)
                }
            }
        }
        return out.toList()
    }
}
