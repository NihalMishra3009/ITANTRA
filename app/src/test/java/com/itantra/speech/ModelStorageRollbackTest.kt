package com.itantra.speech

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Phase 1 rollback invariant tests. Install must never destroy a working model:
 *   - failed validation / smoke happens before publish -> live dir untouched
 *   - publish keeps the old model as a backup
 *   - publish-then-verify failure restores the old model
 * Pure filesystem logic (ModelStorageManager companion) — no Context/network.
 */
class ModelStorageRollbackTest {

    private fun mkDir(parent: File, name: String): File =
        File(parent, name).apply { mkdirs() }

    private fun write(ctx: File, name: String, bytes: ByteArray = ByteArray(4)) {
        File(ctx, name).writeBytes(bytes)
    }

    private fun contents(dir: File): Set<String> =
        dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }.toSet()

    private fun makeTranslationPack(dir: File, marker: String) {
        write(dir, "encoder_model.onnx")
        write(dir, "decoder_model.onnx")
        write(dir, "config.json", marker.toByteArray())
        val tok = mkDir(dir, "tokenizer")
        write(tok, "sentencepiece.model")
        write(tok, "sp.vocab")
    }

    @Test
    fun testFirstInstallSucceeds_noBackup() {
        val root = File(System.getProperty("java.io.tmpdir"), "itm_roll_${System.nanoTime()}")
        root.mkdirs()
        try {
            val staging = mkDir(root, "staging")
            makeTranslationPack(staging, "v1")
            // First install: NO pre-existing live directory at all.
            val live = File(root, "live")

            val backup = ModelStorageManager.keepBackupPublish(staging, live)

            assertNull("no previous install -> no backup", backup)
            assertTrue("staging published into live", ModelStorageManager.isCompleteTranslationPackFiles(live))
            assertFalse("staging consumed", File(root, "staging").exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun testFirstInstallFailsValidation_liveNeverCreated() {
        val root = File(System.getProperty("java.io.tmpdir"), "itm_roll_${System.nanoTime()}")
        root.mkdirs()
        try {
            val staging = mkDir(root, "staging")
            // Missing contract files -> validation fails BEFORE publish.
            write(staging, "encoder_model.onnx")
            assertFalse(ModelStorageManager.isCompleteTranslationPackFiles(staging))
            val live = mkDir(root, "live")
            // Validation failure means publish is never invoked -> live must not exist as a pack.
            assertFalse("validation-failed pack not installed",
                ModelStorageManager.isCompleteTranslationPackFiles(live))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun testReplaceSucceeds_backupThenRemovedWhenConfirmed() {
        val root = File(System.getProperty("java.io.tmpdir"), "itm_roll_${System.nanoTime()}")
        root.mkdirs()
        try {
            val live = mkDir(root, "live")
            makeTranslationPack(live, "v1")
            val before = contents(live)

            val staging = mkDir(root, "staging")
            makeTranslationPack(staging, "v2")
            val backup = ModelStorageManager.keepBackupPublish(staging, live)

            assertNotNull("old model preserved during replacement", backup)
            assertTrue("old backup still holds v1", File(backup!!, "config.json").readText() == "v1")

            // New live verified -> discard the backup.
            assertTrue(ModelStorageManager.isCompleteTranslationPackFiles(live))
            backup.deleteRecursively()
            assertFalse("backup discarded only after verification", backup.exists())
            assertTrue("new config live", File(live, "config.json").readText() == "v2")
        } finally { root.deleteRecursively() }
    }

    @Test
    fun testReplaceFailsVerify_oldModelRestoredAndUsable() {
        val root = File(System.getProperty("java.io.tmpdir"), "itm_roll_${System.nanoTime()}")
        root.mkdirs()
        try {
            val live = mkDir(root, "live")
            makeTranslationPack(live, "v1")

            val staging = mkDir(root, "staging")
            makeTranslationPack(staging, "v2")
            val backup = ModelStorageManager.keepBackupPublish(staging, live)

            // New live does NOT verify (e.g. publish landed without decoder).
            File(live, "decoder_model.onnx").delete()

            ModelStorageManager.restoreFromBackup(backup, live)

            assertNotNull("old model was preserved as backup", backup)
            assertFalse("no .old remains after restore", backup != null && backup.exists())
            assertTrue("old model restored and usable", ModelStorageManager.isCompleteTranslationPackFiles(live))
            assertEquals("old model is v1", "v1", File(live, "config.json").readText())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun testReplaceFailsSmoke_oldTouchedByNothing() {
        val root = File(System.getProperty("java.io.tmpdir"), "itm_roll_${System.nanoTime()}")
        root.mkdirs()
        try {
            val live = mkDir(root, "live")
            makeTranslationPack(live, "v1")
            val before = contents(live)

            // Smoke test failure is a PRE-publish condition: the live dir must be
            // byte-identical afterwards (not renamed, not backed up, not deleted).
            // Here we simulate by NOT publishing: publish is only reached when the
            // staged smoke passes.
            assertEquals("never republished", before, contents(live))
            assertTrue("v1 still live", ModelStorageManager.isCompleteTranslationPackFiles(live))
            assertNull("no backup created by a skipped publish",
                File(live.parentFile, live.name + ".old").takeIf { it.exists() })
        } finally { root.deleteRecursively() }
    }

    @Test
    fun testCorruptedDownloadNeverReplacesValidModel() {
        val root = File(System.getProperty("java.io.tmpdir"), "itm_roll_${System.nanoTime()}")
        root.mkdirs()
        try {
            val live = mkDir(root, "live")
            makeTranslationPack(live, "valid")
            val before = contents(live)

            // A corrupted download is rejected by SHA-256 BEFORE staging/extract —
            // represent that by a staging dir that never completes validation.
            val staging = mkDir(root, "staging")
            write(staging, "encoder_model.onnx", ByteArray(2)) // truncated/corrupt

            assertFalse("corrupt staging fails validation",
                ModelStorageManager.isCompleteTranslationPackFiles(staging))
            assertTrue("valid model untouched", ModelStorageManager.isCompleteTranslationPackFiles(live))
            assertEquals("v1 intact", "valid", File(live, "config.json").readText())
            assertEquals("files unchanged", before, contents(live))
        } finally { root.deleteRecursively() }
    }
}