package net.kigawa.recl.storage.fortis

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FortisConditionalBatchTest {
    private val key = "recl/v1/t/tenant-a/c/0190d6c2-1a2b-7c3d-8e4f-000000000001"

    @Test
    fun `batch mirrors the fortis limits and uniqueness rules`() {
        assertThrows(IllegalArgumentException::class.java) {
            FortisConditionalBatch("p", "r", emptyList(), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            FortisConditionalBatch("p", "r", emptyList(), listOf(
                FortisMutation.Put(key, byteArrayOf(1)), FortisMutation.Put(key, byteArrayOf(2)),
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FortisConditionalBatch("p", "r", emptyList(), listOf(
                FortisMutation.Put(key, ByteArray(FortisConditionalBatch.MAX_VALUE_BYTES + 1)),
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FortisConditionalBatch("p", "r", emptyList(), listOf(FortisMutation.Put("k".repeat(1025), byteArrayOf(1))))
        }
    }

    @Test
    fun `canonical digest depends on conditions and mutations but not on object identity`() {
        val a = FortisConditionalBatch("p", "r", listOf(FortisCondition.Absent(key)), listOf(FortisMutation.Put(key, byteArrayOf(1))))
        val same = FortisConditionalBatch("p", "r", listOf(FortisCondition.Absent(key)), listOf(FortisMutation.Put(key, byteArrayOf(1))))
        val other = FortisConditionalBatch("p", "r", listOf(FortisCondition.RevisionEquals(key, 1)), listOf(FortisMutation.Put(key, byteArrayOf(1))))
        assertArrayEquals(a.canonicalDigest(), same.canonicalDigest())
        assertFalse(a.canonicalDigest().contentEquals(other.canonicalDigest()))
    }
}
