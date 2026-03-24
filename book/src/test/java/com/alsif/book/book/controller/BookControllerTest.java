package com.alsif.book.book.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

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

@ExtendWith(MockitoExtension.class)
class BookControllerTest {

	@Mock
	private BookService bookService;

	@Mock
	private TaskManager taskManager;

	@InjectMocks
	private BookController bookController;

	@Test
	void bookReturnsOkResponseAndManagesTaskLifecycle() {
		ConcertSeatBookRequestDto requestDto = ConcertSeatBookRequestDto.builder()
			.seatSeqs(List.of(1L, 2L))
			.build();
		SuccessResponseDto successResponseDto = SuccessResponseDto.builder()
			.message("true")
			.build();

		when(taskManager.checkTask(requestDto.getSeatSeqs())).thenReturn(true);
		when(bookService.book(7, 11, requestDto)).thenReturn(successResponseDto);

		ResponseEntity<SuccessResponseDto> response = bookController.book(11, 7, requestDto);

		assertEquals(HttpStatus.OK, response.getStatusCode());
		assertEquals("true", response.getBody().getMessage());
		verify(taskManager).addTask(requestDto.getSeatSeqs());
		verify(taskManager).removeTask(requestDto.getSeatSeqs());
		verify(bookService).book(7, 11, requestDto);
	}
}
