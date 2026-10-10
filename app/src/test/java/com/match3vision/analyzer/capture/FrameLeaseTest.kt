package com.match3vision.analyzer.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FrameLeaseTest {
    @Test
    fun frozenFrame_staysReadableBesideANewerFrame() {
        val lease = FrameLease()
        val frozen = intArrayOf(1, 2, 3)
        assertThat(lease.publish(1L, frozen)).isEmpty()
        lease.retain(1L)
        frozen[0] = 9

        val freeable = lease.publish(2L, intArrayOf(4, 5, 6))
        assertThat(freeable).doesNotContain(1L)
        assertThat(lease.canFree(1L)).isFalse()
        assertThat(lease.held(1L)).isTrue()
        assertThat(lease.read(1L)!!.asList()).containsExactly(1, 2, 3).inOrder()
        assertThat(lease.read(2L)!!.asList()).containsExactly(4, 5, 6).inOrder()

        val parallel = lease.read(1L)!!
        lease.publish(3L, intArrayOf(7, 8, 9))
        assertThat(parallel.asList()).containsExactly(1, 2, 3).inOrder()
        assertThat(lease.read(3L)!!.asList()).containsExactly(7, 8, 9).inOrder()
        assertThat(lease.canFree(3L)).isFalse()

        assertThat(lease.release(1L)).isTrue()
        assertThat(lease.canFree(1L)).isTrue()
        lease.free(1L)
        assertThat(lease.read(1L)).isNull()
        assertThat(lease.canFree(2L)).isTrue()
        lease.free(2L)
        assertThat(lease.read(2L)).isNull()
        assertThat(lease.read(3L)).isNotNull()
    }
}
