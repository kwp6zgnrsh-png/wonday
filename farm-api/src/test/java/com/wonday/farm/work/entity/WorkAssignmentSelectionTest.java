package com.wonday.farm.work.entity;

import static com.wonday.farm.work.testsupport.SelectionFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.wonday.farm.job.dto.CreateJobPostingRequest;
import com.wonday.farm.job.entity.WorkSchedule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

// 순수 엔티티 단위 테스트. JPA 매핑·DB 컬럼 저장은 확인하지 않는다.
@DisplayName("WORK-03 신규 약정 생성(WorkAssignment)·JobApplication.select()")
class WorkAssignmentSelectionTest {

	@Test
	@DisplayName("잠근 공고·일정에서 날짜·시간대·주소·좌표·작목·작업 유형·시작/종료·휴게·일급·버전·확정 시각을 복사")
	void copiesTerms() {
		var posting = posting(2);
		var schedule = schedule(posting, WorkSchedule.Status.OPEN, 4);
		var assignment = new WorkAssignment(APPLICATION, schedule, NOW_UTC);
		assertThat(assignment.getApplicationId()).isEqualTo(APPLICATION);
		assertThat(assignment.getAgreedWorkDate()).isEqualTo(DAY);
		assertThat(assignment.getAgreedTimeZone()).isEqualTo("GMT+9");
		assertThat(assignment.getAgreedWorkLocation()).isEqualTo("가상 작업지 1");
		assertThat(assignment.getAgreedLatitude()).isEqualByComparingTo("37.123456");
		assertThat(assignment.getAgreedLongitude()).isEqualByComparingTo("127.654321");
		assertThat(assignment.getAgreedCropCode()).isEqualTo("FC03030100030103");
		assertThat(assignment.getAgreedWorkTypeCode()).isEqualTo("0003");
		assertThat(assignment.getAgreedStartAt()).isEqualTo(DAY.atTime(9, 0));
		assertThat(assignment.getAgreedEndAt()).isEqualTo(DAY.atTime(17, 0));
		assertThat(assignment.getAgreedBreakStartAt()).isEqualTo(DAY.atTime(12, 0));
		assertThat(assignment.getAgreedBreakEndAt()).isEqualTo(DAY.atTime(13, 0));
		assertThat(assignment.getAgreedDailyWage()).isEqualTo(120000L);
		assertThat(assignment.getAgreedTermsVersion()).isEqualTo(4);
		assertThat(assignment.getConfirmedAtUtc()).isEqualTo(NOW_UTC);
		assertThat(assignment.getConfirmedAt()).isEqualTo(NOW_UTC);
		assertThat(assignment.getCompletedAtUtc()).isNull();
		assertThat(assignment.getCompletedBy()).isNull();
	}

	@Test
	@DisplayName("휴게 없음은 휴게 시작·종료 모두 null로 저장")
	void noBreak() {
		var posting = posting(2);
		var schedule = new WorkSchedule(posting, new CreateJobPostingRequest.Schedule(DAY, DAY.atTime(9, 0), DAY.atTime(13, 0), null, null));
		var assignment = new WorkAssignment(APPLICATION, schedule, NOW_UTC);
		assertThat(assignment.getAgreedBreakStartAt()).isNull();
		assertThat(assignment.getAgreedBreakEndAt()).isNull();
	}

	@Test
	@DisplayName("이후 일정·공고 값이 바뀌어도 저장된 약정은 그대로 (복사본)")
	void snapshotIsIndependent() {
		var posting = posting(2);
		var schedule = schedule(posting, WorkSchedule.Status.OPEN, 3);
		var assignment = new WorkAssignment(APPLICATION, schedule, NOW_UTC);
		var later = LocalDate.parse("2026-12-11");
		schedule.updateConditions(new CreateJobPostingRequest.Schedule(later, later.atTime(10, 0), later.atTime(18, 0), null, null));
		ReflectionTestUtils.setField(posting, "dailyWage", 150000L);
		ReflectionTestUtils.setField(posting, "workLocation", "바뀐 작업지");
		ReflectionTestUtils.setField(posting, "latitude", new BigDecimal("35.000000"));
		assertThat(schedule.getTermsVersion()).isEqualTo(4);
		assertThat(assignment.getAgreedTermsVersion()).isEqualTo(3);
		assertThat(assignment.getAgreedWorkDate()).isEqualTo(DAY);
		assertThat(assignment.getAgreedStartAt()).isEqualTo(DAY.atTime(9, 0));
		assertThat(assignment.getAgreedBreakStartAt()).isEqualTo(DAY.atTime(12, 0));
		assertThat(assignment.getAgreedDailyWage()).isEqualTo(120000L);
		assertThat(assignment.getAgreedWorkLocation()).isEqualTo("가상 작업지 1");
		assertThat(assignment.getAgreedLatitude()).isEqualByComparingTo("37.123456");
	}

	@Test
	@DisplayName("좌표 없는 공고도 엔티티는 null을 그대로 복사(0 등으로 보완하지 않음) — 신규 확정 차단은 서비스 책임")
	void missingCoordinatesNotFilled() {
		var posting = posting(2);
		ReflectionTestUtils.setField(posting, "latitude", null);
		ReflectionTestUtils.setField(posting, "longitude", null);
		var assignment = new WorkAssignment(APPLICATION, schedule(posting, WorkSchedule.Status.OPEN, 3), NOW_UTC);
		assertThat(assignment.getAgreedLatitude()).isNull();
		assertThat(assignment.getAgreedLongitude()).isNull();
	}

	@Test
	@DisplayName("지원 ID 0 이하·일정 null·시각 null → IllegalArgumentException")
	void invalidArguments() {
		var schedule = schedule(posting(2), WorkSchedule.Status.OPEN, 3);
		assertThatThrownBy(() -> new WorkAssignment(0, schedule, NOW_UTC)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new WorkAssignment(APPLICATION, null, NOW_UTC)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new WorkAssignment(APPLICATION, schedule, null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("JobApplication.select(): 현재 회차 PENDING만 SELECTED, 최초 지원 시각·버전·회차 유지, 그 외 IllegalState")
	void select() {
		var app = application(2, "PENDING", true);
		var appliedAt = app.getAppliedAtUtc();
		var legacyAt = app.getAppliedAt();
		app.select();
		assertThat(app.getStatus()).isEqualTo("SELECTED");
		assertThat(app.getAppliedAtUtc()).isEqualTo(appliedAt);
		assertThat(app.getAppliedAt()).isEqualTo(legacyAt);
		assertThat(app.getAppliedTermsVersion()).isEqualTo(2);
		assertThat(app.getApplicationRound()).isEqualTo(2);
		assertThat(app.isCurrent()).isTrue();
		assertThatThrownBy(app::select).isInstanceOf(IllegalStateException.class);
		for (var status : new String[] {"NOT_SELECTED", "CANCELLED"}) {
			var other = application(2, status, true);
			assertThatThrownBy(other::select).isInstanceOf(IllegalStateException.class);
			assertThat(other.getStatus()).isEqualTo(status);
		}
		var retired = application(2, "PENDING", false);
		assertThatThrownBy(retired::select).isInstanceOf(IllegalStateException.class);
		assertThat(retired.getStatus()).isEqualTo("PENDING");
		assertThat(LocalDateTime.parse("2026-11-20T01:02:03.456789")).isEqualTo(appliedAt);
	}
}
