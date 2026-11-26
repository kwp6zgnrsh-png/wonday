package com.wonday.farm.job.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.wonday.farm.job.dto.CreateJobPostingRequest;
import com.wonday.farm.job.dto.MyJobPostingsResponse;
import com.wonday.farm.job.dto.WorkerJobPostingResponse;
import com.wonday.farm.job.dto.WorkerJobPostingsResponse;
import com.wonday.farm.job.entity.JobPosting;
import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.job.repository.JobPostingRepository;
import com.wonday.farm.job.repository.WorkScheduleRepository;
import com.wonday.farm.job.testsupport.JobPostingFixtures;
import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.reference.AgriculturalCodeCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 고용주 내 공고·근로자 목록·상세 응답의 좌표 연결만 본다 (APP-01 전체 정책은 기존 테스트 범위).
// Repository는 mock이고 공고·일정은 실제 엔티티다. 좌표 없는 기존 공고는 ReflectionTestUtils로 null을 넣어 재현한다.
// JSON은 기본 JsonMapper로 직렬화한다. Spring Boot의 실제 ObjectMapper 설정과 HTTP 응답은 이 테스트로 증명하지 않는다.
@DisplayName("공고 조회 응답의 좌표 (내 공고 · 근로자 목록 · 상세)")
class JobLocationQueryTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final long EMPLOYER_ID = 10L;
	private static final long WORKER_ID = 5L;

	private final MemberRepository members = mock(MemberRepository.class);
	private final JobPostingRepository postings = mock(JobPostingRepository.class);
	private final WorkScheduleRepository schedules = mock(WorkScheduleRepository.class);
	private final Clock clock = Clock.fixed(Instant.parse("2026-11-26T00:00:00Z"), ZoneOffset.UTC);
	private Member employer;
	private JobPosting located;
	private JobPosting other;
	private JobPosting legacy;
	private WorkSchedule locatedDay;
	private WorkSchedule otherDay;
	private WorkSchedule legacyDay;

	@BeforeEach
	void setUp() {
		employer = member(EMPLOYER_ID, MemberRole.EMPLOYER);
		given(members.findById(EMPLOYER_ID)).willReturn(Optional.of(employer));
		given(members.findById(WORKER_ID)).willReturn(Optional.of(member(WORKER_ID, MemberRole.WORKER)));
		located = posting(3L, "37.1234565", "127.9876544");
		other = posting(2L, "-33.8688", "151.2093");
		legacy = posting(1L, "1", "1");
		ReflectionTestUtils.setField(legacy, "latitude", null);
		ReflectionTestUtils.setField(legacy, "longitude", null);
		locatedDay = schedule(located, 30L);
		otherDay = schedule(other, 20L);
		legacyDay = schedule(legacy, 10L);
	}

	@Nested
	@DisplayName("고용주 내 공고 (/mine)")
	class Mine {
		private final JobPostingQueryService service = new JobPostingQueryService(members, postings, schedules);

		@BeforeEach
		void stored() {
			given(postings.findByEmployer_IdOrderByIdDesc(EMPLOYER_ID)).willReturn(List.of(located, other, legacy));
			given(schedules.countOwnedSchedules(EMPLOYER_ID)).willReturn(List.of());
			given(schedules.findOwnedSchedules(EMPLOYER_ID)).willReturn(List.of(legacyDay, otherDay, locatedDay));
		}

		@Test
		@DisplayName("공고마다 저장된 좌표를 그대로 연결하고 섞지 않으며, 좌표 없는 공고는 두 필드 모두 null로 함께 반환한다")
		void coordinatesPerPosting() {
			var result = service.findMine(EMPLOYER_ID).jobPostings();

			assertThat(result).extracting(MyJobPostingsResponse.Posting::jobPostingId).containsExactly(3L, 2L, 1L);
			assertThat(result.get(0).latitude()).isSameAs(located.getLatitude());
			assertThat(result.get(0).longitude()).isSameAs(located.getLongitude());
			assertThat(result.get(0).latitude().toPlainString()).isEqualTo("37.123457");
			assertThat(result.get(0).longitude().toPlainString()).isEqualTo("127.987654");
			assertThat(result.get(1).latitude().toPlainString()).isEqualTo("-33.868800");
			assertThat(result.get(1).longitude().toPlainString()).isEqualTo("151.209300");
			assertThat(result.get(2).latitude()).isNull();
			assertThat(result.get(2).longitude()).isNull();
			assertThat(result.get(2).schedules()).extracting(MyJobPostingsResponse.Schedule::workScheduleId).containsExactly(10L);
		}

		@Test
		@DisplayName("JSON에서 좌표는 숫자(문자열 아님), 기존 공고는 명시적 null이며 기존 필드·일정 조건 버전을 보존한다")
		void json() {
			JsonNode postingsJson = JSON.readTree(JSON.writeValueAsString(service.findMine(EMPLOYER_ID))).get("jobPostings");

			assertThat(postingsJson.get(0).get("latitude").isNumber()).isTrue();
			assertThat(postingsJson.get(0).get("latitude").decimalValue()).isEqualByComparingTo("37.123457");
			assertThat(postingsJson.get(0).get("longitude").decimalValue()).isEqualByComparingTo("127.987654");
			assertThat(postingsJson.get(2).has("latitude")).isTrue();
			assertThat(postingsJson.get(2).get("latitude").isNull()).isTrue();
			assertThat(postingsJson.get(2).get("longitude").isNull()).isTrue();
			assertThat(postingsJson.get(0).get("schedules").get(0).get("termsVersion").asInt()).isEqualTo(1);
			assertThat(postingsJson.get(0).get("schedules").get(0).get("workDate").asString()).isEqualTo("2026-11-30");
			assertThat(Arrays.stream(MyJobPostingsResponse.Posting.class.getRecordComponents()).map(c -> c.getName()))
				.containsExactly("jobPostingId", "title", "description", "cropCode", "workTypeCode", "workLocation",
					"timeZone", "headcount", "dailyWage", "schedules", "latitude", "longitude");
		}

		@Test
		@DisplayName("조회는 엔티티 좌표를 바꾸거나 저장하지 않는다")
		void readOnly() {
			service.findMine(EMPLOYER_ID);
			service.findMine(EMPLOYER_ID);

			assertUnchanged();
			verify(postings, never()).save(any());
			verify(schedules, never()).saveAll(any());
		}
	}

	@Nested
	@DisplayName("근로자 목록·상세")
	class Worker {
		private final WorkerJobPostingQueryService service =
			new WorkerJobPostingQueryService(members, postings, schedules, new AgriculturalCodeCatalog(), clock);

		@BeforeEach
		void stored() {
			given(postings.findRecruitmentCandidates(any(), any(), any(), any())).willReturn(List.of(located, other, legacy));
			given(schedules.findForWorkerPostings(anyCollection())).willAnswer(invocation -> {
				var ids = invocation.<java.util.Collection<Long>>getArgument(0);
				return List.of(locatedDay, otherDay, legacyDay).stream()
					.filter(day -> ids.contains(day.getJobPosting().getId())).toList();
			});
			given(schedules.countWorkerScheduleAssignments(anyCollection())).willReturn(List.of());
			given(schedules.findWorkerApplications(anyLong(), anyCollection())).willReturn(List.of());
			given(postings.findById(3L)).willReturn(Optional.of(located));
			given(postings.findById(1L)).willReturn(Optional.of(legacy));
		}

		@Test
		@DisplayName("목록: 공고마다 좌표를 연결하고, 좌표 없는 공고도 숨기지 않으며 지원 가능 여부가 같다")
		void list() {
			var result = service.list(WORKER_ID, 0, 20).jobPostings();

			assertThat(result).extracting(WorkerJobPostingsResponse.Posting::jobPostingId).containsExactly(3L, 2L, 1L);
			assertThat(result.get(0).latitude().toPlainString()).isEqualTo("37.123457");
			assertThat(result.get(0).longitude().toPlainString()).isEqualTo("127.987654");
			assertThat(result.get(1).latitude().toPlainString()).isEqualTo("-33.868800");
			assertThat(result.get(1).longitude().toPlainString()).isEqualTo("151.209300");
			assertThat(result.get(2).latitude()).isNull();
			assertThat(result.get(2).longitude()).isNull();
			assertThat(result).allSatisfy(posting -> {
				assertThat(posting.recruitingSchedules()).singleElement().satisfies(day -> {
					assertThat(day.canApply()).isTrue();
					assertThat(day.unavailableReason()).isNull();
					assertThat(day.remainingSlots()).isEqualTo(2);
				});
			});
		}

		@Test
		@DisplayName("상세: 저장 좌표를 연결하고, 좌표 없는 공고는 두 필드 null이며 날짜·조건 버전·지원 가능 여부는 그대로다")
		void detail() {
			WorkerJobPostingResponse withCoordinates = service.detail(WORKER_ID, 3L);
			WorkerJobPostingResponse withoutCoordinates = service.detail(WORKER_ID, 1L);

			assertThat(withCoordinates.latitude().toPlainString()).isEqualTo("37.123457");
			assertThat(withCoordinates.longitude().toPlainString()).isEqualTo("127.987654");
			assertThat(withoutCoordinates.latitude()).isNull();
			assertThat(withoutCoordinates.longitude()).isNull();
			for (var response : List.of(withCoordinates, withoutCoordinates)) {
				assertThat(response.schedules()).singleElement().satisfies(day -> {
					assertThat(day.termsVersion()).isEqualTo(1);
					assertThat(day.workDate()).hasToString("2026-11-30");
					assertThat(day.canApply()).isTrue();
				});
				assertThat(response.workLocation()).isEqualTo("충청북도 충주시 예시농장");
			}
		}

		@Test
		@DisplayName("JSON: 목록·상세 모두 좌표는 숫자, 좌표 없는 공고는 명시적 null")
		void json() {
			JsonNode list = JSON.readTree(JSON.writeValueAsString(service.list(WORKER_ID, 0, 20))).get("jobPostings");
			JsonNode detail = JSON.readTree(JSON.writeValueAsString(service.detail(WORKER_ID, 3L)));
			JsonNode legacyDetail = JSON.readTree(JSON.writeValueAsString(service.detail(WORKER_ID, 1L)));

			assertThat(list.get(0).get("latitude").isNumber()).isTrue();
			assertThat(list.get(0).get("longitude").decimalValue()).isEqualByComparingTo("127.987654");
			assertThat(list.get(2).get("latitude").isNull()).isTrue();
			assertThat(list.get(2).get("longitude").isNull()).isTrue();
			assertThat(detail.get("latitude").isNumber()).isTrue();
			assertThat(detail.get("latitude").decimalValue()).isEqualByComparingTo("37.123457");
			assertThat(legacyDetail.has("longitude")).isTrue();
			assertThat(legacyDetail.get("longitude").isNull()).isTrue();
		}

		@Test
		@DisplayName("목록·상세 조회는 엔티티 좌표를 바꾸거나 저장하지 않는다")
		void readOnly() {
			service.list(WORKER_ID, 0, 20);
			service.detail(WORKER_ID, 3L);
			service.detail(WORKER_ID, 1L);

			assertUnchanged();
			verify(postings, never()).save(any());
			verify(schedules, never()).saveAll(any());
		}
	}

	private void assertUnchanged() {
		assertThat(located.getLatitude().toPlainString()).isEqualTo("37.123457");
		assertThat(located.getLongitude().toPlainString()).isEqualTo("127.987654");
		assertThat(other.getLatitude().toPlainString()).isEqualTo("-33.868800");
		assertThat(legacy.getLatitude()).isNull();
		assertThat(legacy.getLongitude()).isNull();
	}

	private JobPosting posting(long id, String latitude, String longitude) {
		var request = new CreateJobPostingRequest("공고 " + id, "내용", "VC02100100100102", "0058", "충청북도 충주시 예시농장",
			new BigDecimal(latitude), new BigDecimal(longitude), "GMT+9", 2, 120000L, List.of(JobPostingFixtures.defaultSchedule()));
		var posting = new JobPosting(employer, request);
		ReflectionTestUtils.setField(posting, "id", id);
		return posting;
	}

	private static WorkSchedule schedule(JobPosting posting, long id) {
		var schedule = new WorkSchedule(posting, JobPostingFixtures.defaultSchedule());
		ReflectionTestUtils.setField(schedule, "id", id);
		return schedule;
	}

	private static Member member(long id, MemberRole role) {
		var member = new Member("member-" + id, "hash", "회원 " + id, role);
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}
}
