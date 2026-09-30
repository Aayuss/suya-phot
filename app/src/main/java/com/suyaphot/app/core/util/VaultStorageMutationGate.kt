package com.suyaphot.app.core.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes destructive or ownership-sensitive mutations of vault storage roots.
 *
 * Startup orphan cleanup and backup restore can otherwise race: cleanup may see a restore's
 * not-yet-committed vault directory or staging area as orphaned and delete it while restore
 * is still using it.
 */
object VaultStorageMutationGate {
    private val mutex = Mutex()

    suspend fun <T> withExclusiveMutation(block: suspend () -> T): T =
        mutex.withLock { block() }
}
