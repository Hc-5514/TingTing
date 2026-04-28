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

	@InjectMocks
	private BookService bookService;

	@Test
	// 좌석과 포인트가 모두 정상일 때 예매 성공 처리와 후속 저장 작업이 수행되는지 검증한다.
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

		// 예매 성공 후 좌석 상태 변경, 티켓-좌석 저장, 포인트 차감이 모두 이어져야 한다.
		SuccessResponseDto response = bookService.book(1, 10, requestDto);

		assertEquals("true", response.getMessage());
		verify(concertSeatInfoRepository).updateBooks(seatSeqs, true);
		verify(jdbcRepository).saveTicketSeats(seatSeqs, savedTicket);

		// 총 좌석 가격(1000 + 2000)만큼 pay는 음수로 기록되고, total은 차감된 잔액이어야 한다.
		ArgumentCaptor<Point> pointCaptor = ArgumentCaptor.forClass(Point.class);
		verify(pointRepository).save(pointCaptor.capture());
		assertEquals(-3000, pointCaptor.getValue().getPay());
		assertEquals(2000, pointCaptor.getValue().getTotal());

			// 예매가 완료된 좌석은 Redis에도 점유 상태로 기록되어야 한다.
			verify(redisService).setValue(BookService.CONCERT_SEAT_INFO_KEY + 1L, "1");
			verify(redisService).setValue(BookService.CONCERT_SEAT_INFO_KEY + 2L, "1");
		}

	@Test
	// Redis에 이미 점유 정보가 있으면 DB 조회 이전에 즉시 예매를 차단해야 한다.
	void bookFailsWhenSeatAlreadyReservedInRedis() {
		List<Long> seatSeqs = List.of(1L, 2L);
		ConcertSeatBookRequestDto requestDto = ConcertSeatBookRequestDto.builder()
			.seatSeqs(seatSeqs)
			.build();

		when(redisService.getValue(BookService.CONCERT_SEAT_INFO_KEY + 1L)).thenReturn("1");

		CustomException exception = assertThrows(CustomException.class,
			() -> bookService.book(1, 10, requestDto));

		assertEquals(ErrorCode.NOT_AVAILABLE_SEAT, exception.getErrorCode());
			// 검증 실패 시 실제 예매 저장 로직은 진행되면 안 된다.
			verify(concertSeatInfoRepository, never()).updateBooks(any(), eq(true));
			verify(ticketRepository, never()).save(any(Ticket.class));
		}

	@Test
	// Redis에는 비어 있어도 DB 기준으로 이미 예매된 좌석이 있으면 실패해야 한다.
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
			// DB 검증 단계에서 막혔기 때문에 좌석 상태 변경과 티켓 저장은 모두 발생하지 않아야 한다.
			verify(concertSeatInfoRepository, never()).updateBooks(any(), eq(true));
			verify(ticketRepository, never()).save(any(Ticket.class));
		}

	@Test
	// 좌석 점유에는 성공했더라도 보유 포인트가 부족하면 결제 단계에서 예매가 실패해야 한다.
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
			// 현재 구현 기준으로 좌석 상태 변경과 티켓 저장 후 포인트 차감 단계에서 실패가 발생한다.
			verify(concertSeatInfoRepository).updateBooks(seatSeqs, true);
			verify(jdbcRepository).saveTicketSeats(seatSeqs, savedTicket);
			// 결제가 완료되지 않았으므로 포인트 이력 저장과 Redis 점유 확정은 수행되지 않아야 한다.
			verify(pointRepository, never()).save(any(Point.class));
			verify(redisService, never()).setValue(any(), any());
	}
}
