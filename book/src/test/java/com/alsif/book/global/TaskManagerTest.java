package com.alsif.book.global;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class TaskManagerTest {

	@Test
	void tryAcquireAllowsOnlyOneThreadForSameSeat() throws InterruptedException {
		TaskManager taskManager = new TaskManager();
		List<Long> seatSeqs = List.of(1L, 2L);

		int threadCount = 20;
		CountDownLatch readyLatch = new CountDownLatch(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(threadCount);
		ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
		AtomicInteger successCount = new AtomicInteger();

		try {
			for (int i = 0; i < threadCount; i++) {
				executorService.submit(() -> {
					readyLatch.countDown();
					try {
						assertTrue(startLatch.await(2, TimeUnit.SECONDS));
						if (taskManager.tryAcquire(seatSeqs)) {
							successCount.incrementAndGet();
						}
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
					} finally {
						doneLatch.countDown();
					}
				});
			}

			assertTrue(readyLatch.await(2, TimeUnit.SECONDS));
			startLatch.countDown();
			assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
		} finally {
			executorService.shutdownNow();
		}

		assertEquals(1, successCount.get());
	}

	@Test
	void tryAcquireFailsWhenAnySeatAlreadyLocked() {
		TaskManager taskManager = new TaskManager();

		assertTrue(taskManager.tryAcquire(List.of(1L, 2L)));
		assertFalse(taskManager.tryAcquire(List.of(2L, 3L)));
	}

	@Test
	void releaseAllowsReacquire() {
		TaskManager taskManager = new TaskManager();
		List<Long> seatSeqs = new ArrayList<>(List.of(3L, 4L));

		assertTrue(taskManager.tryAcquire(seatSeqs));
		taskManager.release(seatSeqs);
		assertTrue(taskManager.tryAcquire(seatSeqs));
	}
}
