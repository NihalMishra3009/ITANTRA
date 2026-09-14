package com.itantra.speech

import org.junit.Assert.*
import org.junit.Test

/** Phase 11: archive-entry path policy rejects traversal/absolute/encoded escapes. */
class ArchiveEntrySecurityTest {

    @Test
    fun testNormalRelativePathsAccepted() {
        assertTrue(ModelStorageManager.isSafeArchiveEntryPath("hi-en/models/encoder_model.onnx"))
        assertTrue(ModelStorageManager.isSafeArchiveEntryPath("tokenizer/sp.vocab"))
        assertTrue(ModelStorageManager.isSafeArchiveEntryPath("config.json"))
        assertTrue(ModelStorageManager.isSafeArchiveEntryPath("model.onnx"))
    }

    @Test
    fun testDotDotTraversalRejected() {
        assertFalse(ModelStorageManager.isSafeArchiveEntryPath("../evil.sh"))
        assertFalse(ModelStorageManager.isSafeArchiveEntryPath("a/../../etc/passwd"))
        assertFalse(ModelStorageManager.isSafeArchiveEntryPath("..%2F..%2Fetc"))
        assertFalse(ModelStorageManager.isSafeArchiveEntryPath("..%5c..%5cwindows"))
        assertFalse(ModelStorageManager.isSafeArchiveEntryPath("%2e%2e/escape"))
    }

    @Test
    fun testAbsoluteAndDrivePathsRejected() {
        assertFalse(ModelStorageManager.isSafeArchiveEntryPath("/etc/passwd"))
        assertFalse(ModelStorageManager.isSafeArchiveEntryPath("\\etc\\passwd"))
        assertFalse(ModelStorageManager.isSafeArchiveEntryPath("C:\\Windows\\evil.exe"))
        assertFalse(ModelStorageManager.isSafeArchiveEntryPath("c:/windows/evil.exe"))
    }

    @Test
    fun testEmptyRejected() {
        assertFalse(ModelStorageManager.isSafeArchiveEntryPath(""))
    }
}