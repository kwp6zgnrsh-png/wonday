package com.wonday.farm.job.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.wonday.farm.job.dto.CreateJobPostingRequest;
import com.wonday.farm.job.dto.CreateJobPostingResponse;
import com.wonday.farm.job.entity.JobPosting;
import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.job.exception.JobPostingException;
import com.wonday.farm.job.repository.JobPostingRepository;
import com.wonday.farm.job.repository.WorkScheduleRepository;
import com.wonday.farm.job.testsupport.JobPostingFixtures;
import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

// 공고 등록 서비스와 JobPosting 생성자의 좌표 처리. Repository는 mock, 공고·일정은 실제 엔티티다.
// 실제 INSERT·DECIMAL(9,6) 저장 정밀도·CHECK 제약·롤백은 mock으로 증명할 수 없다 (미검증 범위).
@DisplayName("공고 등록 서비스·엔티티의 좌표 저장")
class JobLocationServiceTest {
	private static final long EMPLOYER_ID = 10L;

	private final MemberRepository members = mock(MemberRepository.class);
	private final JobPostingRepository postings = mock(JobPostingRepository.class);
	private final WorkScheduleRepository schedules = mock(WorkScheduleRepository.class);
	private final JobPostingService service = new JobPostingService(members, postings, schedules);
	private final AtomicLong ids = new AtomicLong(100);
	private Member employer;

	@BeforeEach
	void setUp() {
		employer = member(EMPLOYER_ID, MemberRole.EMPLOYER);
		given(members.findById(EMPLOYER_ID)).willReturn(Optional.of(employer));
		given(postings.save(any())).willAnswer(invocation -> withId(invocation.getArgument(0)));
		given(schedules.saveAll(anyList())).willAnswer(invocation -> {
			List<Object> saved = new ArrayList<>();
			for (Object schedule : (Iterable<?>) invocation.getArgument(0)) saved.add(withId(schedule));
			return saved;
		});
	}

	@Nested
	@DisplayName("정상 등록")
	class Success {
		@Test
		@DisplayName("Repository에 전달된 공고에 주소와 HALF_UP 6자리 좌표가 함께 담긴다 (위도·경도 순서 유지)")
		void savesRoundedCoordinatesWithAddress() {
			service.create(EMPLOYER_ID, request("37.1234565", "127.9876544"));

			JobPosting saved = capturedPosting();
			assertThat(saved.getWorkLocation()).isEqualTo("충청북도 충주시 예시농장");
			assertThat(saved.getLatitude().toPlainString()).isEqualTo("37.123457");
			assertThat(saved.getLongitude().toPlainString()).isEqualTo("127.987654");
			assertThat(saved.getLatitude().scale()).isEqualTo(6);
			assertThat(saved.getLongitude().scale()).isEqualTo(6);
		}

		@ParameterizedTest(name = "({0}, {1}) → ({2}, {3})")
		@CsvSource({"37.12345678, 127.12345678, 37.123457, 127.123457", "-37.1234565, -127.1234565, -37.123457, -127.123457",
			"89.9999999, 179.9999999, 90.000000, 180.000000", "0, 0, 0.000000, 0.000000", "-90, -180, -90.000000, -180.000000",
			"12.5, 120, 12.500000, 120.000000"})
		@DisplayName("여러 좌표 원본의 저장값 (경계·0·짧은 소수 포함)")
		void savesNormalizedValues(String latitude, String longitude, String savedLatitude, String savedLongitude) {
			service.create(EMPLOYER_ID, request(latitude, longitude));

			JobPosting saved = capturedPosting();
			assertThat(saved.getLatitude().toPlainString()).isEqualTo(savedLatitude);
			assertThat(saved.getLongitude().toPlainString()).isEqualTo(savedLongitude);
		}

		@Test
		@DisplayName("소유자는 세션 회원(DB 조회 결과)이고, 공고 저장 후 같은 공고로 일정을 저장한다. 응답은 기존 ID 계약 그대로다")
		void ownerScheduleAndResponseContractUnchanged() {
			CreateJobPostingResponse response = service.create(EMPLOYER_ID, request("37.1", "127.1"));

			var order = inOrder(members, postings, schedules);
			order.verify(members).findById(EMPLOYER_ID);
			ArgumentCaptor<JobPosting> posting = ArgumentCaptor.forClass(JobPosting.class);
			order.verify(postings).save(posting.capture());
			@SuppressWarnings("unchecked")
			ArgumentCaptor<List<WorkSchedule>> saved = ArgumentCaptor.forClass(List.class);
			order.verify(schedules).saveAll(saved.capture());

			assertThat(posting.getValue().getEmployer()).isSameAs(employer);
			assertThat(saved.getValue()).allSatisfy(schedule -> assertThat(schedule.getJobPosting()).isSameAs(posting.getValue()));
			assertThat(response.jobPostingId()).isEqualTo(posting.getValue().getId());
			assertThat(response.employerId()).isEqualTo(EMPLOYER_ID);
			assertThat(response.schedules()).hasSize(1);
			assertThat(Arrays.stream(CreateJobPostingResponse.class.getRecordComponents()).map(c -> c.getName()))
				.containsExactly("jobPostingId", "employerId", "headcount", "schedules");
		}

		@Test
		@DisplayName("요청의 원본 BigDecimal은 저장 과정에서 바뀌지 않는다")
		void requestOriginalUntouched() {
			var request = request("37.12345678", "127.12345678");

			service.create(EMPLOYER_ID, request);

			assertThat(request.latitude().toPlainString()).isEqualTo("37.12345678");
			assertThat(request.longitude().toPlainString()).isEqualTo("127.12345678");
		}
	}

	@Nested
	@DisplayName("좌표 검증 실패 — 저장 호출 없음")
	class Rejected {
		@ParameterizedTest(name = "lat={0}, lon={1} → {2}")
		@CsvSource(nullValues = "NULL", value = {
			"90.0000004, 127.1, latitude", "-90.0000004, 127.1, latitude", "90.0000000000000001, 127.1, latitude",
			"37.1, 180.0000004, longitude", "37.1, -180.0000004, longitude", "NULL, 127.1, latitude",
			"37.1, NULL, longitude", "NULL, NULL, latitude"})
		@DisplayName("Bean Validation을 거치지 않고 서비스를 직접 호출해도 엔티티 생성에서 INVALID_WORK_LOCATION으로 거절하고 공고·일정을 저장하지 않는다")
		void invalidCoordinatesNeverSaved(String latitude, String longitude, String field) {
			assertThatThrownBy(() -> service.create(EMPLOYER_ID, request(latitude, longitude)))
				.isInstanceOfSatisfying(JobPostingException.class, error -> {
					assertThat(error.code()).isEqualTo("INVALID_WORK_LOCATION");
					assertThat(error.field()).isEqualTo(field);
				});
			verify(postings, never()).save(any());
			verify(schedules, never()).saveAll(anyList());
		}

		@Test
		@DisplayName("고용주가 아니면 좌표가 정상이어도 기존처럼 AccessDenied이고 저장하지 않는다")
		void roleCheckUnchanged() {
			given(members.findById(EMPLOYER_ID)).willReturn(Optional.of(member(EMPLOYER_ID, MemberRole.WORKER)));

			assertThatThrownBy(() -> service.create(EMPLOYER_ID, request("37.1", "127.1"))).isInstanceOf(AccessDeniedException.class);
			verify(postings, never()).save(any());
		}

		@Test
		@DisplayName("기존 일정·시간대 검증이 좌표 검증보다 먼저 실행된다 (시간대 오류와 좌표 오류가 함께 있으면 INVALID_TIME_ZONE)")
		void existingValidationRunsFirst() {
			var request = new CreateJobPostingRequest("제목", "내용", "C", "W", "주소", new BigDecimal("91"), new BigDecimal("1"),
				"Asia/Seoul", 2, 120000L, List.of(JobPostingFixtures.defaultSchedule()));

			assertThatThrownBy(() -> service.create(EMPLOYER_ID, request))
				.isInstanceOfSatisfying(JobPostingException.class, error -> assertThat(error.code()).isEqualTo("INVALID_TIME_ZONE"));
			verify(postings, never()).save(any());
		}
	}

	@Nested
	@DisplayName("엔티티 생성자")
	class Entity {
		@Test
		@DisplayName("JobPosting 생성자도 원본 범위를 검사한다 (90.0000004 거절, 89.9999999 → 90.000000)")
		void constructorValidates() {
			assertThatThrownBy(() -> new JobPosting(employer, request("90.0000004", "127.1")))
				.isInstanceOfSatisfying(JobPostingException.class, error -> assertThat(error.field()).isEqualTo("latitude"));
			assertThatThrownBy(() -> new JobPosting(employer, request("37.1", "-180.0000004")))
				.isInstanceOfSatisfying(JobPostingException.class, error -> assertThat(error.field()).isEqualTo("longitude"));

			var posting = new JobPosting(employer, request("89.9999999", "-179.9999999"));
			assertThat(posting.getLatitude().toPlainString()).isEqualTo("90.000000");
			assertThat(posting.getLongitude().toPlainString()).isEqualTo("-180.000000");
		}

		@Test
		@DisplayName("요청 DTO에는 고용주 ID 필드가 없다 — 소유자를 클라이언트가 지정할 수 없음")
		void noEmployerIdInRequest() {
			assertThat(Arrays.stream(CreateJobPostingRequest.class.getRecordComponents()).map(c -> c.getName().toLowerCase()))
				.noneMatch(name -> name.contains("employer") || name.contains("owner") || name.contains("memberid"));
		}
	}

	private JobPosting capturedPosting() {
		ArgumentCaptor<JobPosting> captor = ArgumentCaptor.forClass(JobPosting.class);
		verify(postings).save(captor.capture());
		return captor.getValue();
	}

	private static CreateJobPostingRequest request(String latitude, String longitude) {
		return new CreateJobPostingRequest("사과 수확 근로자 모집", "사과 수확과 운반 작업입니다.", "TEST-CROP", "TEST-WORK",
			"충청북도 충주시 예시농장", latitude == null ? null : new BigDecimal(latitude),
			longitude == null ? null : new BigDecimal(longitude), "GMT+9", 2, 120000L, List.of(JobPostingFixtures.defaultSchedule()));
	}

	private static Member member(long id, MemberRole role) {
		var member = new Member("member-" + id, "hash", "회원 " + id, role);
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	private Object withId(Object entity) {
		ReflectionTestUtils.setField(entity, "id", ids.incrementAndGet());
		return entity;
	}
}
