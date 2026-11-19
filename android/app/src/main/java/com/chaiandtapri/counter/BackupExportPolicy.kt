package com.chaiandtapri.counter

/** Records whether an incomplete SAF destination was removed after an export failed. */
internal class BackupExportException(
    cause: Exception,
    val partialDocumentRemoved: Boolean
) : Exception("Encrypted backup export did not complete.", cause)

/** Keeps failed or partial backup files from being mistaken for usable recovery snapshots. */
internal object BackupExportPolicy {
    fun <T> write(writeDocument: () -> T, deletePartialDocument: () -> Boolean): T {
        try {
            return writeDocument()
        } catch (failure: Exception) {
            val removed = runCatching(deletePartialDocument).getOrDefault(false)
            throw BackupExportException(failure, removed)
        }
    }
}
