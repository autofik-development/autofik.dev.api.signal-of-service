package com.autofik.sos.dto.event;

import java.math.BigDecimal;
import java.util.UUID;

public record RatingUpdateEvent (
		UUID mechanicId,
		BigDecimal avgRate,
		int ratingCount){

}
