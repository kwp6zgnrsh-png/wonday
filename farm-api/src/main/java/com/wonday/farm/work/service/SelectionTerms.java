package com.wonday.farm.work.service;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import com.wonday.farm.job.dto.CreateJobPostingRequest;
import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.job.exception.JobPostingException;
import com.wonday.farm.job.validation.WorkScheduleValidator;
import com.wonday.farm.work.exception.WorkSelectionException;
import tools.jackson.databind.json.JsonMapper;

// 선정과 재확인의 상태·시간·버전 규칙을 한곳에서 사용한다.
public final class SelectionTerms {
    private SelectionTerms() {}
    public static void requireState(WorkSchedule schedule) {
        if (schedule.getStatus() != WorkSchedule.Status.OPEN && schedule.getStatus() != WorkSchedule.Status.PAUSED) {
            throw WorkSelectionException.conflict("SELECTION_NOT_ALLOWED", "모집 중 또는 일시중단인 일정에서만 처리할 수 있습니다.");
        }
    }
    public static void requireVersion(WorkSchedule schedule, int expected) {
        if (schedule.getTermsVersion() != expected) {
            throw WorkSelectionException.conflict("TERMS_VERSION_CONFLICT", "근무 조건이 변경되었습니다. 최신 조건을 확인한 뒤 다시 요청해 주세요.");
        }
    }
    public static ZoneId zone(WorkSchedule schedule) {
        String value = schedule.getJobPosting().getTimeZone();
        try { if (value != null && !value.isBlank()) return ZoneId.of(value); }
        catch (DateTimeException ignored) { }
        throw WorkSelectionException.conflict("WORK_TERMS_UNAVAILABLE", "작업지 시간대와 근무 조건을 먼저 보완해 주세요.");
    }
    public static void requireBeforeStart(WorkSchedule schedule, Instant now) {
        var start = schedule.getPlannedStartAt();
        var zone = zone(schedule);
        if (start == null || zone.getRules().getValidOffsets(start).size() != 1) throw incomplete();
        if (!now.isBefore(start.toInstant(zone.getRules().getValidOffsets(start).getFirst()))) {
            throw WorkSelectionException.conflict("WORK_ALREADY_STARTED", "근무 시작 시각 전까지만 처리할 수 있습니다.");
        }
    }
    public static void requireConditions(WorkSchedule schedule) {
        var posting = schedule.getJobPosting();
        if (schedule.getTermsVersion() <= 0 || schedule.getWorkDate() == null
                || schedule.getPlannedStartAt() == null || schedule.getPlannedEndAt() == null
                || posting.getHeadcount() <= 0 || posting.getDailyWage() <= 0
                || blank(posting.getWorkLocation()) || blank(posting.getCropCode()) || blank(posting.getWorkTypeCode())) {
            throw incomplete();
        }
        try {
            WorkScheduleValidator.validate(new CreateJobPostingRequest.Schedule(schedule.getWorkDate(),
                schedule.getPlannedStartAt(), schedule.getPlannedEndAt(), schedule.getBreakStartAt(), schedule.getBreakEndAt()),
                zone(schedule), "");
        } catch (JobPostingException exception) { throw incomplete(); }
    }
    public static void requireCoordinates(WorkSchedule schedule) {
        var posting = schedule.getJobPosting();
        var latitude = posting.getLatitude(); var longitude = posting.getLongitude();
        if (latitude == null || longitude == null || latitude.abs().compareTo(java.math.BigDecimal.valueOf(90)) > 0
                || longitude.abs().compareTo(java.math.BigDecimal.valueOf(180)) > 0) {
            throw WorkSelectionException.conflict("WORK_LOCATION_INCOMPLETE", "신규 근무 확정 전에 작업지 주소와 좌표를 보완해 주세요.");
        }
    }
    public static String snapshot(WorkSchedule schedule, JsonMapper mapper) {
        var posting = schedule.getJobPosting();
        var values = new LinkedHashMap<String, Object>();
        values.put("termsVersion", schedule.getTermsVersion()); values.put("workDate", schedule.getWorkDate().toString());
        values.put("timeZone", posting.getTimeZone()); values.put("workLocation", posting.getWorkLocation());
        values.put("latitude", posting.getLatitude()); values.put("longitude", posting.getLongitude());
        values.put("cropCode", posting.getCropCode()); values.put("workTypeCode", posting.getWorkTypeCode());
        values.put("description", posting.getDescription()); values.put("headcount", posting.getHeadcount());
        values.put("dailyWage", posting.getDailyWage()); values.put("plannedStartAt", schedule.getPlannedStartAt().toString());
        values.put("plannedEndAt", schedule.getPlannedEndAt().toString());
        values.put("breakStartAt", schedule.getBreakStartAt() == null ? null : schedule.getBreakStartAt().toString());
        values.put("breakEndAt", schedule.getBreakEndAt() == null ? null : schedule.getBreakEndAt().toString());
        return mapper.writeValueAsString(values);
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static WorkSelectionException incomplete() {
        return WorkSelectionException.conflict("WORK_TERMS_UNAVAILABLE", "공고의 날짜·시간·약정 조건을 먼저 보완해 주세요.");
    }
}
