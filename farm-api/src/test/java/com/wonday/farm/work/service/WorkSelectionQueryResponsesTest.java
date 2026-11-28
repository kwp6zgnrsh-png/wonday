package com.wonday.farm.work.service;

import static com.wonday.farm.application.testsupport.ApplicationTestData.member;
import static com.wonday.farm.work.testsupport.WorkApplicantRows.EMPLOYER;
import static com.wonday.farm.work.testsupport.WorkApplicantRows.POSTING;
import static com.wonday.farm.work.testsupport.WorkApplicantRows.SCHEDULE;
import static com.wonday.farm.work.testsupport.WorkApplicantRows.row;
import static com.wonday.farm.work.testsupport.WorkApplicantRows.schedule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.wonday.farm.application.repository.MyApplicationQueryRepository;
import com.wonday.farm.application.service.ApplicationConditionMapper;
import com.wonday.farm.job.repository.WorkScheduleRepository;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.reference.AgriculturalCodeCatalog;
import com.wonday.farm.work.repository.WorkApplicantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

// WORK-01 서비스는 실제 서비스·매퍼 + 저장소 mock. SQL은 문자열 선언 검사이며 실제 MySQL의
// LEFT JOIN 중복·서브쿼리·정렬·타입 변환 결과를 확인하지 않는다.
@DisplayName("WORK-01·APP-04 확인 상태 응답과 조회 SQL 선언")
class WorkSelectionQueryResponsesTest {
	private static final LocalDateTime AT = LocalDateTime.parse("2026-11-28T06:00:00.123456");
	private final MemberRepository members = mock(MemberRepository.class);
	private final WorkApplicantRepository repository = mock(WorkApplicantRepository.class);
	private final WorkScheduleRepository schedules = mock(WorkScheduleRepository.class);
	private final WorkApplicantQueryService service = new WorkApplicantQueryService(members, repository, schedules,
		new ApplicationConditionMapper(new AgriculturalCodeCatalog()));

	@BeforeEach
	void owned() {
		given(members.findById(EMPLOYER)).willReturn(Optional.of(member(EMPLOYER, MemberRole.EMPLOYER)));
		given(repository.findOwnedPosting(EMPLOYER, POSTING)).willReturn(Optional.of(POSTING));
		given(repository.findOwnedSchedule(EMPLOYER, POSTING, SCHEDULE)).willReturn(Optional.of(schedule("OPEN", 3)));
		given(schedules.countOccupyingAssignments(SCHEDULE)).willReturn(0L);
	}

	private static Set<String> aliases(String sql) {
		var matcher = Pattern.compile("(?i)\\bas\\s+([A-Za-z][A-Za-z0-9]*)").matcher(sql);
		var found = new java.util.HashSet<String>();
		while (matcher.find()) found.add(matcher.group(1));
		return found;
	}
	private static Set<String> getters(Class<?> type) {
		return Arrays.stream(type.getMethods()).filter(m -> m.getName().startsWith("get") && m.getParameterCount() == 0
				&& !Modifier.isStatic(m.getModifiers()) && !m.isDefault())
			.map(Method::getName).map(n -> Character.toLowerCase(n.charAt(3)) + n.substring(4)).collect(Collectors.toSet());
	}

	@Test
	@DisplayName("WORK-01 목록·상세: 최근 재확인을 conditionConfirmation으로 전달, CHANGED이면서 latestTermsConfirmed=true")
	void workApplicants() {
		var reconfirmed = row(v -> {
			v.put("applicationId", 501L); v.put("appliedTermsVersion", 2); v.put("latestTermsVersion", 3);
			v.put("acknowledgementId", 9001L); v.put("acknowledgedTermsVersion", 3); v.put("acknowledgedAtUtc", AT);
		});
		given(repository.findCurrent(anyLong(), anyLong(), anyLong(), anyString(), any(), any(), anyInt())).willReturn(List.of(reconfirmed));
		given(repository.findCurrentOwned(EMPLOYER, POSTING, SCHEDULE, 501)).willReturn(Optional.of(reconfirmed));
		var item = service.list(EMPLOYER, POSTING, SCHEDULE, null, null, null).items().getFirst();
		assertThat(item.conditionComparisonStatus()).isEqualTo("CHANGED");
		var info = item.conditionConfirmation();
		assertThat(info.source()).isEqualTo("RECONFIRMATION");
		assertThat(info.latestTermsConfirmed()).isTrue();
		assertThat(info.acknowledgementId()).isEqualTo(9001L);
		assertThat(info.acknowledgedAt()).isEqualTo(OffsetDateTime.parse("2026-11-28T15:00:00.123456+09:00"));
		var detail = service.detail(EMPLOYER, POSTING, SCHEDULE, 501);
		assertThat(detail.conditionConfirmation()).isEqualTo(info);
		assertThat(detail.conditionComparisonStatus()).isEqualTo("CHANGED");
	}

	@Test
	@DisplayName("WORK-01: 마지막 재확인이 이전 버전이면 최신 확인 필요(false)·과거 기록 유지, 기록 없으면 최초 지원 기준")
	void workApplicantsStale() {
		var stale = row(v -> {
			v.put("appliedTermsVersion", 1); v.put("latestTermsVersion", 3);
			v.put("acknowledgementId", 8001L); v.put("acknowledgedTermsVersion", 2); v.put("acknowledgedAtUtc", AT);
		});
		var none = row(v -> { v.put("applicationId", 502L); v.put("appliedTermsVersion", 2); v.put("latestTermsVersion", 3); });
		given(repository.findCurrent(anyLong(), anyLong(), anyLong(), anyString(), any(), any(), anyInt())).willReturn(List.of(stale, none));
		var items = service.list(EMPLOYER, POSTING, SCHEDULE, null, null, null).items();
		assertThat(items.get(0).conditionConfirmation().latestTermsConfirmed()).isFalse();
		assertThat(items.get(0).conditionConfirmation().acknowledgedTermsVersion()).isEqualTo(2);
		assertThat(items.get(0).conditionConfirmation().acknowledgementId()).isEqualTo(8001L);
		assertThat(items.get(1).conditionConfirmation().source()).isEqualTo("INITIAL_APPLICATION");
		assertThat(items.get(1).conditionConfirmation().latestTermsConfirmed()).isFalse();
	}

	@Test
	@DisplayName("선언: APP-04 조회는 같은 지원(app.id)의 최대 버전 재확인 한 건만 LEFT JOIN (다른 회차 기록 미연결)")
	void myApplicationJoin() {
		String from = MyApplicationQueryRepository.FROM_ROW.replaceAll("\\s+", " ");
		assertThat(from).contains("left join application_condition_acknowledgement acknowledgement on acknowledgement.application_id = app.id"
			+ " and acknowledgement.terms_version = (select max(saved.terms_version) from application_condition_acknowledgement saved"
			+ " where saved.application_id = app.id)");
		assertThat(from).doesNotContain("acknowledgement.application_id = assignment", "saved.application_id = acknowledgement");
		assertThat(aliases(MyApplicationQueryRepository.SELECT_ROW)).contains("acknowledgementId", "acknowledgedTermsVersion", "acknowledgedAtUtc");
		assertThat(MyApplicationQueryRepository.SELECT_ROW).contains("acknowledgement.id as acknowledgementId",
			"acknowledgement.terms_version as acknowledgedTermsVersion", "acknowledgement.acknowledged_at_utc as acknowledgedAtUtc");
	}

	@Test
	@DisplayName("선언: 조회 행 getter마다 SQL 별칭 존재 (APP-04 Row ⊂ SELECT_ROW, WORK-01 Row ⊂ SELECT) — 누락 별칭은 projection에서 조용히 null")
	void aliasesCoverRows() {
		assertThat(aliases(MyApplicationQueryRepository.SELECT_ROW)).containsAll(getters(MyApplicationQueryRepository.Row.class));
		assertThat(aliases(WorkApplicantRepository.SELECT)).containsAll(getters(WorkApplicantRepository.Row.class));
		assertThat(WorkApplicantRepository.FROM).contains(MyApplicationQueryRepository.FROM_ROW);
	}

	@Test
	@DisplayName("선언: Row를 반환하는 APP-04·WORK-01 조회는 모두 SELECT_ROW·FROM_ROW(재확인 JOIN 포함)를 사용")
	void everyRowQueryUsesJoin() {
		for (var type : List.of(MyApplicationQueryRepository.class, WorkApplicantRepository.class)) {
			int checked = 0;
			for (var method : type.getDeclaredMethods()) {
				var query = method.getAnnotation(Query.class);
				if (query == null) continue;
				boolean returnsRow = method.getGenericReturnType().getTypeName().endsWith("$Row>")
					|| method.getGenericReturnType().getTypeName().endsWith("$Row");
				if (!returnsRow) continue;
				assertThat(query.value()).as(type.getSimpleName() + "." + method.getName())
					.contains("application_condition_acknowledgement acknowledgement", "as acknowledgedAtUtc");
				checked++;
			}
			assertThat(checked).as(type.getSimpleName() + " Row 조회 수").isGreaterThanOrEqualTo(2);
		}
	}
}
