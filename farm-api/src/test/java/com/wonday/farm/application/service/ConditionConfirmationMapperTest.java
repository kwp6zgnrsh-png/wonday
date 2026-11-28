package com.wonday.farm.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import com.wonday.farm.application.dto.ConditionConfirmation;
import com.wonday.farm.application.testsupport.MyApplicationRows;
import com.wonday.farm.reference.AgriculturalCodeCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// ConditionConfirmationMapper 순수 매핑 + APP-04 ApplicationConditionMapper 목록·상세 연결.
// 조회 행은 getter 이름에 맞춘 가상 값이다. 실제 LEFT JOIN·최근 재확인 서브쿼리 결과는 확인하지 않는다.
@DisplayName("WORK-04 확인 상태 매핑 (ConditionConfirmationMapper·APP-04 목록/상세)")
class ConditionConfirmationMapperTest {
	private static final LocalDateTime AT = LocalDateTime.parse("2026-11-28T06:00:00.123456");
	private static final OffsetDateTime AT_KST = OffsetDateTime.parse("2026-11-28T15:00:00.123456+09:00");
	private final ApplicationConditionMapper mapper = new ApplicationConditionMapper(new AgriculturalCodeCatalog());

	private static void expect(ConditionConfirmation info, String source, Integer confirmed, boolean latest, Long id, Integer version, OffsetDateTime at) {
		assertThat(info.source()).isEqualTo(source);
		assertThat(info.confirmedTermsVersion()).isEqualTo(confirmed);
		assertThat(info.latestTermsConfirmed()).isEqualTo(latest);
		assertThat(info.acknowledgementId()).isEqualTo(id);
		assertThat(info.acknowledgedTermsVersion()).isEqualTo(version);
		assertThat(info.acknowledgedAt()).isEqualTo(at);
	}

	@Test
	@DisplayName("최초 지원 버전 = 최신 → INITIAL_APPLICATION·true, 재확인 필드 null")
	void initialLatest() {
		expect(ConditionConfirmationMapper.of(3, 3, null, null, null), "INITIAL_APPLICATION", 3, true, null, null, null);
	}

	@Test
	@DisplayName("최초 ≠ 최신 + 최신 버전 재확인 → RECONFIRMATION·true·ID/버전/시각(+09:00)")
	void reconfirmed() {
		expect(ConditionConfirmationMapper.of(2, 3, 9001L, 3, AT), "RECONFIRMATION", 3, true, 9001L, 3, AT_KST);
	}

	@Test
	@DisplayName("마지막 재확인이 이전 버전 → RECONFIRMATION·false, 과거 기록(ID·버전·시각) 유지")
	void staleReconfirmation() {
		expect(ConditionConfirmationMapper.of(1, 3, 9001L, 2, AT), "RECONFIRMATION", 2, false, 9001L, 2, AT_KST);
	}

	@Test
	@DisplayName("최초 ≠ 최신 + 재확인 없음 → INITIAL_APPLICATION·false (최초 버전이 근거)")
	void initialOld() {
		expect(ConditionConfirmationMapper.of(2, 3, null, null, null), "INITIAL_APPLICATION", 2, false, null, null, null);
	}

	@Test
	@DisplayName("최초 버전·재확인 모두 없음 → NONE·null·false (최신 버전을 추측하지 않음)")
	void none() {
		expect(ConditionConfirmationMapper.of(null, 3, null, null, null), "NONE", null, false, null, null, null);
	}

	@Test
	@DisplayName("최초 버전 없음 + 최신 재확인 → RECONFIRMATION·true")
	void legacyReconfirmed() {
		expect(ConditionConfirmationMapper.of(null, 3, 9001L, 3, AT), "RECONFIRMATION", 3, true, 9001L, 3, AT_KST);
	}

	@Test
	@DisplayName("APP-04 목록·상세: 조회 행의 최근 재확인 값을 conditionConfirmation으로 전달, CHANGED이면서 latestTermsConfirmed=true 가능")
	void myApplications() {
		var row = MyApplicationRows.row(v -> {
			v.put("appliedTermsVersion", 2); v.put("latestTermsVersion", 3);
			v.put("acknowledgementId", 9001L); v.put("acknowledgedTermsVersion", 3); v.put("acknowledgedAtUtc", AT);
		});
		var item = mapper.item(row);
		assertThat(item.conditionComparisonStatus()).isEqualTo("CHANGED");
		expect(item.conditionConfirmation(), "RECONFIRMATION", 3, true, 9001L, 3, AT_KST);
		var detail = mapper.detail(row);
		assertThat(detail.conditionComparisonStatus()).isEqualTo("CHANGED");
		assertThat(detail.conditionConfirmation()).isEqualTo(item.conditionConfirmation());
	}

	@Test
	@DisplayName("APP-04: 재확인 행이 없으면(LEFT JOIN null) 최초 지원 기준, 최초 버전도 없으면 NONE·UNKNOWN 비교 유지")
	void myApplicationsWithoutAcknowledgement() {
		var same = mapper.item(MyApplicationRows.row(v -> {}));
		assertThat(same.conditionComparisonStatus()).isEqualTo("UNCHANGED");
		expect(same.conditionConfirmation(), "INITIAL_APPLICATION", 3, true, null, null, null);
		var legacy = mapper.item(MyApplicationRows.row(v -> v.put("appliedTermsVersion", null)));
		assertThat(legacy.conditionComparisonStatus()).isEqualTo("UNKNOWN");
		expect(legacy.conditionConfirmation(), "NONE", null, false, null, null, null);
	}
}
