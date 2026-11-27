package com.wonday.farm.application.service;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.wonday.farm.application.dto.MyApplicationResponses;
import com.wonday.farm.application.dto.MyApplicationResponses.Conditions;
import com.wonday.farm.application.repository.MyApplicationQueryRepository.Row;
import com.wonday.farm.reference.AgriculturalCodeCatalog;
import org.springframework.stereotype.Component;

// 조회 전용 매핑이다. 과거 값이나 확정 약정을 현재 공고 조건으로 덮어쓰지 않는다.
@Component
public class ApplicationConditionMapper {
	private static final ZoneOffset DISPLAY_OFFSET = ZoneOffset.ofHours(9);
	private static final List<String> FIELDS = List.of("workDate", "timeZone", "workLocation", "latitude",
		"longitude", "cropCode", "workTypeCode", "description", "headcount", "dailyWage",
		"plannedStartAt", "plannedEndAt", "breakStartAt", "breakEndAt");
	private final AgriculturalCodeCatalog codes;
	public ApplicationConditionMapper(AgriculturalCodeCatalog codes) { this.codes = codes; }

	public MyApplicationResponses.Item item(Row row) {
		return new MyApplicationResponses.Item(row.getApplicationId(), row.getApplicationRound(), row.getCurrentFlag() == 1,
			row.getJobPostingId(), row.getTitle(), row.getWorkScheduleId(), row.getWorkDate(),
			utc(row.getAppliedAtUtc()), row.getAppliedAtUtc() == null ? row.getLegacyAppliedAt() : null,
			row.getAppliedAtUtc() == null ? "LEGACY_LOCAL_UNKNOWN_ZONE" : "UTC",
			row.getApplicationStatus(), row.getDisplayStatus(), cancellationType(row), row.getAssignmentId(),
			utc(row.getCompletedAtUtc()), row.getAppliedTermsVersion(), row.getLatestTermsVersion(), comparisonStatus(row));
	}

	public MyApplicationResponses.Detail detail(Row row) {
		var item = item(row);
		var latest = latest(row);
		var applied = applied(row, latest);
		var unavailable = new LinkedHashSet<>(applied.unavailableFields());
		unavailable.addAll(latest.unavailableFields());
		var oldValues = values(applied);
		var newValues = values(latest);
		var changed = new ArrayList<String>();
		for (String field : FIELDS) {
			if (!unavailable.contains(field) && !equalValue(oldValues.get(field), newValues.get(field))) changed.add(field);
		}
		return new MyApplicationResponses.Detail(item.applicationId(), item.applicationRound(), item.current(),
			item.jobPostingId(), item.title(), item.workScheduleId(), item.applicationStatus(), item.displayStatus(),
			item.cancellationType(), item.appliedAt(), item.legacyAppliedAt(), item.appliedTimeBasis(),
			item.appliedTermsVersion(), item.latestTermsVersion(), item.conditionComparisonStatus(),
			applied, latest, List.copyOf(changed), List.copyOf(unavailable), unavailable.isEmpty(), assignment(row));
	}

	private Conditions latest(Row row) {
		return conditions(row.getLatestTermsVersion(), row.getWorkDate(), row.getTimeZone(), row.getWorkLocation(),
			row.getLatitude(), row.getLongitude(), row.getCropCode(), row.getWorkTypeCode(), row.getDescription(),
			row.getHeadcount(), row.getDailyWage(), row.getPlannedStartAt(), row.getPlannedEndAt(),
			row.getBreakStartAt(), row.getBreakEndAt(), List.of());
	}

	private Conditions applied(Row row, Conditions latest) {
		Integer version = row.getAppliedTermsVersion();
		if (version == null) return unavailable(version);
		if (row.getRevisionId() != null) {
			// V6 이력은 좌표·작업 내용을 저장하지 않았다. 그 세 필드는 최신 공고로 채우지 않는다.
			return conditions(version, row.getRevisionWorkDate(), row.getRevisionTimeZone(), row.getRevisionWorkLocation(),
				null, null, row.getRevisionCropCode(), row.getRevisionWorkTypeCode(), null,
				row.getRevisionHeadcount(), row.getRevisionDailyWage(), row.getRevisionStartAt(), row.getRevisionEndAt(),
				row.getRevisionBreakStartAt(), row.getRevisionBreakEndAt(), List.of("latitude", "longitude", "description"));
		}
		// 같은 버전일 때만 현재 조건을 사용한다. 다른 버전인데 기록이 없으면 복원하지 않는다.
		return version == row.getLatestTermsVersion() ? latest : unavailable(version);
	}

	private Conditions unavailable(Integer version) {
		return new Conditions("UNAVAILABLE", version, null, null, null, null, null, null, null, null, null,
			null, null, null, null, null, null, null, FIELDS);
	}

	private Conditions conditions(Integer version, LocalDate day, String timeZone, String location,
			BigDecimal latitude, BigDecimal longitude, String crop, String workType, String description,
			Integer headcount, Long wage, LocalDateTime start, LocalDateTime end,
			LocalDateTime breakStart, LocalDateTime breakEnd, List<String> missing) {
		var unknown = new LinkedHashSet<>(missing);
		if (day == null) unknown.add("workDate");
		if (location == null) unknown.add("workLocation");
		if (latitude == null) unknown.add("latitude");
		if (longitude == null) unknown.add("longitude");
		if (crop == null) unknown.add("cropCode");
		if (workType == null) unknown.add("workTypeCode");
		if (description == null) unknown.add("description");
		if (headcount == null) unknown.add("headcount");
		if (wage == null) unknown.add("dailyWage");
		var zone = zone(timeZone);
		if (zone == null) unknown.add("timeZone");
		var startAt = local(start, zone, "plannedStartAt", unknown);
		var endAt = local(end, zone, "plannedEndAt", unknown);
		OffsetDateTime breakStartAt = null, breakEndAt = null;
		if (breakStart != null || breakEnd != null) {
			breakStartAt = local(breakStart, zone, "breakStartAt", unknown);
			breakEndAt = local(breakEnd, zone, "breakEndAt", unknown);
		}
		return new Conditions(unknown.isEmpty() ? "AVAILABLE" : "PARTIAL", version, day, timeZone, location,
			latitude, longitude, crop, codes.cropName(crop), workType, codes.workTypeName(workType), description,
			headcount, wage, startAt, endAt, breakStartAt, breakEndAt, List.copyOf(unknown));
	}

	private MyApplicationResponses.Assignment assignment(Row row) {
		if (row.getAssignmentId() == null) return null;
		var missing = new LinkedHashSet<String>();
		if (row.getAgreedWorkDate() == null) missing.add("workDate");
		if (row.getAgreedLatitude() == null) missing.add("latitude");
		if (row.getAgreedLongitude() == null) missing.add("longitude");
		if (row.getConfirmedAtUtc() == null) missing.add("confirmedAt");
		var zone = zone(row.getAgreedTimeZone());
		if (zone == null) missing.add("timeZone");
		var start = local(row.getAgreedStartAt(), zone, "plannedStartAt", missing);
		var end = local(row.getAgreedEndAt(), zone, "plannedEndAt", missing);
		OffsetDateTime breakStart = null, breakEnd = null;
		if (row.getAgreedBreakStartAt() != null || row.getAgreedBreakEndAt() != null) {
			breakStart = local(row.getAgreedBreakStartAt(), zone, "breakStartAt", missing);
			breakEnd = local(row.getAgreedBreakEndAt(), zone, "breakEndAt", missing);
		}
		var agreed = new MyApplicationResponses.AgreedConditions(missing.isEmpty() ? "AVAILABLE" : "PARTIAL",
			row.getAgreedWorkDate(), row.getAgreedTimeZone(), row.getAgreedWorkLocation(),
			row.getAgreedLatitude(), row.getAgreedLongitude(), row.getAgreedCropCode(), codes.cropName(row.getAgreedCropCode()),
			row.getAgreedWorkTypeCode(), codes.workTypeName(row.getAgreedWorkTypeCode()), row.getAgreedDailyWage(),
			start, end, breakStart, breakEnd, List.copyOf(missing));
		var change = row.getChangeType() == null ? null : new MyApplicationResponses.Change(row.getChangeType(),
			row.getReasonCode(), row.getWeatherReasonCode(), row.getReasonDetail(), utc(row.getEffectiveAtUtc()),
			utc(row.getRecordedAtUtc()), utc(row.getUpdatedAtUtc()));
		return new MyApplicationResponses.Assignment(row.getAssignmentId(), utc(row.getConfirmedAtUtc()),
			utc(row.getCompletedAtUtc()), agreed, change);
	}

	private static String comparisonStatus(Row row) {
		if (row.getAppliedTermsVersion() == null) return "UNKNOWN";
		return row.getAppliedTermsVersion() == row.getLatestTermsVersion() ? "UNCHANGED" : "CHANGED";
	}
	private static String cancellationType(Row row) {
		if (!"CANCELLED".equals(row.getDisplayStatus())) return null;
		return row.getAssignmentId() == null ? "SCHEDULE_CANCELLED" : "ASSIGNMENT_CANCELLED";
	}
	private static ZoneId zone(String value) {
		try { return value == null || value.isBlank() ? null : ZoneId.of(value); }
		catch (DateTimeException exception) { return null; }
	}
	private static OffsetDateTime local(LocalDateTime value, ZoneId zone, String field, Set<String> missing) {
		if (value == null || zone == null) { missing.add(field); return null; }
		var offsets = zone.getRules().getValidOffsets(value);
		if (offsets.size() != 1) { missing.add(field); return null; }
		return value.atOffset(offsets.getFirst());
	}
	private static OffsetDateTime utc(LocalDateTime value) {
		return value == null ? null : value.toInstant(ZoneOffset.UTC).atOffset(DISPLAY_OFFSET);
	}
	private static boolean equalValue(Object left, Object right) {
		if (left instanceof BigDecimal a && right instanceof BigDecimal b) return a.compareTo(b) == 0;
		return Objects.equals(left, right);
	}
	private static Map<String, Object> values(Conditions c) {
		var values = new LinkedHashMap<String, Object>();
		values.put("workDate", c.workDate()); values.put("timeZone", c.timeZone());
		values.put("workLocation", c.workLocation()); values.put("latitude", c.latitude()); values.put("longitude", c.longitude());
		values.put("cropCode", c.cropCode()); values.put("workTypeCode", c.workTypeCode()); values.put("description", c.description());
		values.put("headcount", c.headcount()); values.put("dailyWage", c.dailyWage());
		values.put("plannedStartAt", c.plannedStartAt()); values.put("plannedEndAt", c.plannedEndAt());
		values.put("breakStartAt", c.breakStartAt()); values.put("breakEndAt", c.breakEndAt());
		return values;
	}
}
