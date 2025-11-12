package io.specmatic.core.utilities

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class TransactionalTest {
    @Test
    fun `initial state should have both committed and working values equal`() {
        val transactional = Transactional(10)
        assertThat(transactional.getCommitted()).isEqualTo(10)
        assertThat(transactional.getWorking()).isEqualTo(10)
        assertThat(transactional.isDirty()).isFalse()
    }

    @Test
    fun `stage should update working value and mark as dirty`() {
        val transactional = Transactional(10)
        transactional.stage(20)

        assertThat(transactional.getCommitted()).isEqualTo(10)
        assertThat(transactional.getWorking()).isEqualTo(20)
        assertThat(transactional.isDirty()).isTrue()
    }

    @Test
    fun `commit should update committed value and clear dirty flag`() {
        val transactional = Transactional(10)
        transactional.stage(20)
        transactional.commit()

        assertThat(transactional.getCommitted()).isEqualTo(20)
        assertThat(transactional.getWorking()).isEqualTo(20)
        assertThat(transactional.isDirty()).isFalse()
    }

    @Test
    fun `rollback should restore working value from committed and clear dirty flag`() {
        val transactional = Transactional(10)
        transactional.stage(20)
        transactional.rollback()

        assertThat(transactional.getCommitted()).isEqualTo(10)
        assertThat(transactional.getWorking()).isEqualTo(10)
        assertThat(transactional.isDirty()).isFalse()
    }

    @Test
    fun `commit without staging should not change state`() {
        val transactional = Transactional(10)
        transactional.commit()

        assertThat(transactional.getCommitted()).isEqualTo(10)
        assertThat(transactional.getWorking()).isEqualTo(10)
        assertThat(transactional.isDirty()).isFalse()
    }

    @Test
    fun `rollback without staging should not change state`() {
        val transactional = Transactional(10)
        transactional.rollback()

        assertThat(transactional.getCommitted()).isEqualTo(10)
        assertThat(transactional.getWorking()).isEqualTo(10)
        assertThat(transactional.isDirty()).isFalse()
    }

    @Test
    fun `multiple stages should only keep the last staged value`() {
        val transactional = Transactional(10)

        transactional.stage(20)
        transactional.stage(30)
        transactional.stage(40)

        assertThat(transactional.getCommitted()).isEqualTo(10)
        assertThat(transactional.getWorking()).isEqualTo(40)
        assertThat(transactional.isDirty()).isTrue()
    }

    @Test
    fun `stage commit stage workflow should work correctly`() {
        val transactional = Transactional(10)

        transactional.stage(20)
        transactional.commit()
        transactional.stage(30)

        assertThat(transactional.getCommitted()).isEqualTo(20)
        assertThat(transactional.getWorking()).isEqualTo(30)
        assertThat(transactional.isDirty()).isTrue()
    }

    @Test
    fun `stage rollback stage workflow should work correctly`() {
        val transactional = Transactional(10)

        transactional.stage(20)
        transactional.rollback()
        transactional.stage(30)

        assertThat(transactional.getCommitted()).isEqualTo(10)
        assertThat(transactional.getWorking()).isEqualTo(30)
        assertThat(transactional.isDirty()).isTrue()
    }

    @Test
    fun `concurrent stages should be thread-safe`() {
        val transactional = Transactional(0)
        val threadCount = 100
        val latch = CountDownLatch(threadCount)

        repeat(threadCount) { i ->
            thread {
                transactional.stage(i)
                latch.countDown()
            }
        }

        latch.await()

        assertThat(transactional.isDirty()).isTrue()
        assertThat(transactional.getWorking()).isIn(0 until threadCount)
        assertThat(transactional.getCommitted()).isEqualTo(0)
    }

    @Test
    fun `concurrent commits should be thread-safe`() {
        val transactional = Transactional(0)
        transactional.stage(42)

        val threadCount = 10
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        repeat(threadCount) {
            executor.submit {
                transactional.commit()
                latch.countDown()
            }
        }

        latch.await()
        executor.shutdown()

        assertThat(transactional.getCommitted()).isEqualTo(42)
        assertThat(transactional.getWorking()).isEqualTo(42)
        assertThat(transactional.isDirty()).isFalse()
    }
}
