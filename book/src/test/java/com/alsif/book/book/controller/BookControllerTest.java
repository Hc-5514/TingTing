package com.alsif.book.book.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.alsif.book.book.service.BookService;
import com.alsif.book.concert.dto.concerthall.ConcertSeatBookRequestDto;
import com.alsif.book.concert.dto.concerthall.SuccessResponseDto;
import com.alsif.book.global.TaskManager;
import com.alsif.book.global.constant.ErrorCode;
import com.alsif.book.global.exception.CustomException;

@ExtendWith(MockitoExtension.class)
class BookControllerTest {

	private static final int THREAD_COUNT = 100;

	@Mock
	private BookService bookService;

	@Mock
	private TaskManager taskManager;

	@InjectMocks
	private BookController bookController;

	@Test
	// 예매 성공 시 Controller가 TaskManager에 작업을 등록하고, 완료 후 정리까지 수행하는지 검증한다.
	void bookReturnsOkResponseAndManagesTaskLifecycle() {
		// 사용자가 선택한 좌석 번호를 포함한 예매 요청을 준비한다.
		ConcertSeatBookRequestDto requestDto = ConcertSeatBookRequestDto.builder()
			.seatSeqs(List.of(1L, 2L))
			.build();
		// Service 계층이 정상적으로 예매를 완료했다고 가정한 응답이다.
		SuccessResponseDto successResponseDto = SuccessResponseDto.builder()
			.message("true")
			.build();

		when(bookService.book(7, 11, requestDto)).thenReturn(successResponseDto);

		// Controller 호출 시 memberSeq와 concertSeq가 Service로 올바르게 전달되는지도 함께 확인한다.
		ResponseEntity<SuccessResponseDto> response = bookController.book(11, 7, requestDto);

		// HTTP 응답과 Task 생명주기, 그리고 Service 위임 여부를 한 번에 검증한다.
		assertEquals(HttpStatus.OK, response.getStatusCode());
		assertEquals("true", response.getBody().getMessage());
		verify(taskManager).addTask(requestDto.getSeatSeqs());
		verify(taskManager).removeTask(requestDto.getSeatSeqs());
		verify(bookService).book(7, 11, requestDto);
	}

	@Test
	// 1~100번 좌석에 대한 100개 동시 예매 요청에서, 이미 예매된 좌석이 하나라도 포함되면 해당 요청은 실패해야 한다.
	void bookFailsWhenAnyRequestedSeatWasAlreadyBooked() throws InterruptedException {
		TaskManager realTaskManager = new TaskManager();
		BookController concurrencyBookController = new BookController(bookService, realTaskManager);
		Set<Long> bookedSeats = ConcurrentHashMap.newKeySet();
		List<List<Long>> ticketRequests = createRandomTicketRequests();
		List<List<Long>> successRequests = new ArrayList<>();
		List<List<Long>> failedRequests = new ArrayList<>();
		List<Throwable> unexpectedErrors = new ArrayList<>();
		CountDownLatch readyLatch = new CountDownLatch(THREAD_COUNT);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);
		ExecutorService executorService = Executors.newFixedThreadPool(THREAD_COUNT);

		when(bookService.book(anyInt(), anyInt(), any(ConcertSeatBookRequestDto.class))).thenAnswer(invocation -> {
			ConcertSeatBookRequestDto requestDto = invocation.getArgument(2);
			List<Long> seatSeqs = requestDto.getSeatSeqs();

			// 실제 예매 흐름처럼 좌석 점유 여부를 확인하고, 하나라도 겹치면 실패로 처리한다.
			synchronized (bookedSeats) {
				if (seatSeqs.stream().anyMatch(bookedSeats::contains)) {
					realTaskManager.removeTask(seatSeqs);
					throw new CustomException(ErrorCode.NOT_AVAILABLE_SEAT);
				}
				bookedSeats.addAll(seatSeqs);
			}

			return SuccessResponseDto.builder()
				.message("true")
				.build();
		});

		try {
			for (List<Long> ticketRequest : ticketRequests) {
				executorService.submit(() -> {
					readyLatch.countDown();

					try {
						// 모든 요청을 동시에 출발시켜 Controller의 대기 및 선점 로직을 검증한다.
						assertTrue(startLatch.await(5, TimeUnit.SECONDS));
						ResponseEntity<SuccessResponseDto> response = concurrencyBookController.book(11, 7,
							ConcertSeatBookRequestDto.builder().seatSeqs(ticketRequest).build());
						assertEquals(HttpStatus.OK, response.getStatusCode());
						synchronized (successRequests) {
							successRequests.add(ticketRequest);
						}
					} catch (CustomException exception) {
						if (exception.getErrorCode() == ErrorCode.NOT_AVAILABLE_SEAT) {
							synchronized (failedRequests) {
								failedRequests.add(ticketRequest);
							}
						} else {
							synchronized (unexpectedErrors) {
								unexpectedErrors.add(exception);
							}
						}
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
						throw new AssertionError(exception);
					} catch (Throwable throwable) {
						synchronized (unexpectedErrors) {
							unexpectedErrors.add(throwable);
						}
					} finally {
						doneLatch.countDown();
					}
				});
			}

			assertTrue(readyLatch.await(5, TimeUnit.SECONDS));
			startLatch.countDown();
			assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
		} finally {
			executorService.shutdownNow();
			assertTrue(executorService.awaitTermination(5, TimeUnit.SECONDS));
		}

		if (!unexpectedErrors.isEmpty()) {
			fail("Unexpected worker error: " + unexpectedErrors.get(0).getClass().getSimpleName());
		}

		assertEquals(THREAD_COUNT, successRequests.size() + failedRequests.size());
		assertTrue(successRequests.size() > 0);
		assertTrue(failedRequests.size() > 0);

		// 성공한 예매끼리는 서로 다른 좌석만 가져야 최종 예약 결과가 일관된다.
		for (int i = 0; i < successRequests.size(); i++) {
			for (int j = i + 1; j < successRequests.size(); j++) {
				assertFalse(hasOverlap(successRequests.get(i), successRequests.get(j)));
			}
		}

		// 실패한 예매는 반드시 이미 성공한 예매의 좌석과 하나 이상 겹쳐야 한다.
		for (List<Long> failedRequest : failedRequests) {
			assertTrue(successRequests.stream().anyMatch(successRequest -> hasOverlap(failedRequest, successRequest)));
		}
	}

	private List<List<Long>> createRandomTicketRequests() {
		Random random = new Random(20260325L);
		List<List<Long>> ticketRequests = new ArrayList<>();

		for (int i = 0; i < THREAD_COUNT; i++) {
			int seatCount = 3 + random.nextInt(3);
			Set<Long> seatSet = new HashSet<>();

			// 한 요청 내부에서는 동일 좌석을 중복 선택하지 않도록 Set으로 생성한다.
			while (seatSet.size() < seatCount) {
				seatSet.add((long)(random.nextInt(100) + 1));
			}

			ticketRequests.add(new ArrayList<>(seatSet));
		}

		return ticketRequests;
	}

	private boolean hasOverlap(List<Long> firstRequest, List<Long> secondRequest) {
		return firstRequest.stream().anyMatch(secondRequest::contains);
	}
}
