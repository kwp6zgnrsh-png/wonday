package com.wonday.farm.insurance.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record WorkAssignmentResponse(
	long assignmentId, long workerId, OffsetDateTime confirmedAt,
	LocalDate agreedWorkDate, String agreedTimeZone, String agreedWorkLocation,
	String agreedCropCode, String agreedWorkTypeCode,
	OffsetDateTime agreedStartAt, OffsetDateTime agreedEndAt,
	OffsetDateTime agreedBreakStartAt, OffsetDateTime agreedBreakEndAt,
	long agreedDailyWage
) {}
