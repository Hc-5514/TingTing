package com.alsif.tingting.concert.dto.concerthall;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@ToString
@Schema(description = "좌석 예매 가능 여부")
public class ConcertSeatBookResponseDto {

	@Schema(description = "좌석 예매 가능 여부", type = "String", example = "true")
	private String message;
}
