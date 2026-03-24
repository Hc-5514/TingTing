package com.alsif.book.book.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.alsif.book.book.entity.Ticket;
import com.alsif.book.book.repository.TicketRepository;
import com.alsif.book.concert.dto.ConcertSeatGradeInfoBaseDto;
import com.alsif.book.concert.dto.concerthall.ConcertSeatBookRequestDto;
import com.alsif.book.concert.dto.concerthall.SuccessResponseDto;
import com.alsif.book.concert.repository.ConcertSeatInfoRepository;
import com.alsif.book.global.TaskManager;
import com.alsif.book.global.constant.ErrorCode;
import com.alsif.book.global.exception.CustomException;
import com.alsif.book.global.repository.JDBCRepository;
import com.alsif.book.global.service.RedisService;
import com.alsif.book.user.entity.Point;
import com.alsif.book.user.repository.PointRepository;

@ExtendWith(MockitoExtension.class)
class BookServiceTest {

	@Mock
	private ConcertSeatInfoRepository concertSeatInfoRepository;

	@Mock
	private TicketRepository ticketRepository;

	@Mock
	private PointRepository pointRepository;

	@Mock
	private RedisService redisService;

	@Mock
	private JDBCRepository jdbcRepository;

	@Mock
	private TaskManager taskManager;

	@InjectMocks
	private BookService bookService;

	@Test
	void bookSuccess() {
		List<Long> seatSeqs = List.of(1L, 2L);
		ConcertSeatBookRequestDto requestDto = ConcertSeatBookRequestDto.builder()
			.seatSeqs(seatSeqs)
			.build();
		List<ConcertSeatGradeInfoBaseDto> seatInfos = List.of(
			ConcertSeatGradeInfoBaseDto.builder().concertSeatInfoSeq(1L).book(false).price(1000).build(),
			ConcertSeatGradeInfoBaseDto.builder().concertSeatInfoSeq(2L).book(false).price(2000).build()
		);
		Point point = Point.builder().pay(0).total(5000).build();
		Ticket savedTicket = Ticket.builder().build();

		when(redisService.getValue(BookService.CONCERT_SEAT_INFO_KEY + 1L)).thenReturn(null);
		when(redisService.getValue(BookService.CONCERT_SEAT_INFO_KEY + 2L)).thenReturn(null);
		when(concertSeatInfoRepository.findByConcertSeatInfoJoinGrade(seatSeqs)).thenReturn(seatInfos);
		when(ticketRepository.save(any(Ticket.class))).thenReturn(savedTicket);
		when(pointRepository.findTop1ByUser_SeqOrderBySeqDesc(1)).thenReturn(Optional.of(point));

		SuccessResponseDto response = bookService.book(1, 10, requestDto);

		assertEquals("true", response.getMessage());
		verify(concertSeatInfoRepository).updateBooks(seatSeqs, true);
		verify(jdbcRepository).saveTicketSeats(seatSeqs, savedTicket);

		ArgumentCaptor<Point> pointCaptor = ArgumentCaptor.forClass(Point.class);
		verify(pointRepository).save(pointCaptor.capture());
		assertEquals(-3000, pointCaptor.getValue().getPay());
		assertEquals(2000, pointCaptor.getValue().getTotal());

		verify(redisService).setValue(BookService.CONCERT_SEAT_INFO_KEY + 1L, "1");
		verify(redisService).setValue(BookService.CONCERT_SEAT_INFO_KEY + 2L, "1");
		verify(taskManager, never()).removeTask(seatSeqs);
	}

	@Test
	void bookFailsWhenSeatAlreadyReservedInRedis() {
		List<Long> seatSeqs = List.of(1L, 2L);
		ConcertSeatBookRequestDto requestDto = ConcertSeatBookRequestDto.builder()
			.seatSeqs(seatSeqs)
			.build();

		when(redisService.getValue(BookService.CONCERT_SEAT_INFO_KEY + 1L)).thenReturn("1");

		CustomException exception = assertThrows(CustomException.class,
			() -> bookService.book(1, 10, requestDto));

		assertEquals(ErrorCode.NOT_AVAILABLE_SEAT, exception.getErrorCode());
		verify(taskManager).removeTask(seatSeqs);
		verify(concertSeatInfoRepository, never()).updateBooks(any(), eq(true));
		verify(ticketRepository, never()).save(any(Ticket.class));
	}

	@Test
	void bookFailsWhenSeatAlreadyBookedInDatabase() {
		List<Long> seatSeqs = List.of(1L, 2L);
		ConcertSeatBookRequestDto requestDto = ConcertSeatBookRequestDto.builder()
			.seatSeqs(seatSeqs)
			.build();
		List<ConcertSeatGradeInfoBaseDto> seatInfos = List.of(
			ConcertSeatGradeInfoBaseDto.builder().concertSeatInfoSeq(1L).book(false).price(1000).build(),
			ConcertSeatGradeInfoBaseDto.builder().concertSeatInfoSeq(2L).book(true).price(2000).build()
		);

		when(redisService.getValue(BookService.CONCERT_SEAT_INFO_KEY + 1L)).thenReturn(null);
		when(redisService.getValue(BookService.CONCERT_SEAT_INFO_KEY + 2L)).thenReturn(null);
		when(concertSeatInfoRepository.findByConcertSeatInfoJoinGrade(seatSeqs)).thenReturn(seatInfos);

		CustomException exception = assertThrows(CustomException.class,
			() -> bookService.book(1, 10, requestDto));

		assertEquals(ErrorCode.NOT_AVAILABLE_SEAT, exception.getErrorCode());
		verify(taskManager).removeTask(seatSeqs);
		verify(concertSeatInfoRepository, never()).updateBooks(any(), eq(true));
		verify(ticketRepository, never()).save(any(Ticket.class));
	}

	@Test
	void bookFailsWhenUserLacksPoint() {
		List<Long> seatSeqs = List.of(1L, 2L);
		ConcertSeatBookRequestDto requestDto = ConcertSeatBookRequestDto.builder()
			.seatSeqs(seatSeqs)
			.build();
		List<ConcertSeatGradeInfoBaseDto> seatInfos = List.of(
			ConcertSeatGradeInfoBaseDto.builder().concertSeatInfoSeq(1L).book(false).price(1000).build(),
			ConcertSeatGradeInfoBaseDto.builder().concertSeatInfoSeq(2L).book(false).price(2000).build()
		);
		Point point = Point.builder().pay(0).total(2000).build();
		Ticket savedTicket = Ticket.builder().build();

		when(redisService.getValue(BookService.CONCERT_SEAT_INFO_KEY + 1L)).thenReturn(null);
		when(redisService.getValue(BookService.CONCERT_SEAT_INFO_KEY + 2L)).thenReturn(null);
		when(concertSeatInfoRepository.findByConcertSeatInfoJoinGrade(seatSeqs)).thenReturn(seatInfos);
		when(ticketRepository.save(any(Ticket.class))).thenReturn(savedTicket);
		when(pointRepository.findTop1ByUser_SeqOrderBySeqDesc(1)).thenReturn(Optional.of(point));

		CustomException exception = assertThrows(CustomException.class,
			() -> bookService.book(1, 10, requestDto));

		assertEquals(ErrorCode.LACK_POINT, exception.getErrorCode());
		verify(concertSeatInfoRepository).updateBooks(seatSeqs, true);
		verify(jdbcRepository).saveTicketSeats(seatSeqs, savedTicket);
		verify(taskManager).removeTask(seatSeqs);
		verify(pointRepository, never()).save(any(Point.class));
		verify(redisService, never()).setValue(any(), any());
	}
}
