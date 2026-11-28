package com.wonday.farm.application.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.Arrays;

import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 엔티티 생성 규칙과 매핑 선언만 확인한다. 실제 V13 테이블·UNIQUE 제약 적용은 확인하지 않는다.
@DisplayName("WORK-04 ApplicationConditionAcknowledgement 엔티티")
class ApplicationConditionAcknowledgementTest {
	private static final LocalDateTime AT = LocalDateTime.parse("2026-11-28T06:00:00.123456");

	@Test
	@DisplayName("정상 값 보관, 변경 메서드(setter) 없음, @Immutable")
	void values() {
		var ack = new ApplicationConditionAcknowledgement(501L, 3, AT, "{\"termsVersion\":3}");
		assertThat(ack.getApplicationId()).isEqualTo(501L);
		assertThat(ack.getTermsVersion()).isEqualTo(3);
		assertThat(ack.getAcknowledgedAtUtc()).isEqualTo(AT);
		assertThat(ack.getConditionsPayload()).isEqualTo("{\"termsVersion\":3}");
		assertThat(ack.getId()).isNull();
		assertThat(Arrays.stream(ApplicationConditionAcknowledgement.class.getMethods()).map(m -> m.getName()))
			.noneMatch(name -> name.startsWith("set"));
		assertThat(ApplicationConditionAcknowledgement.class.isAnnotationPresent(Immutable.class)).isTrue();
	}

	@Test
	@DisplayName("선언: 지원 ID·조건 버전 UNIQUE(uq_application_acknowledgement_version)")
	void unique() {
		var table = ApplicationConditionAcknowledgement.class.getAnnotation(Table.class);
		assertThat(table.name()).isEqualTo("application_condition_acknowledgement");
		assertThat(table.uniqueConstraints()).hasSize(1);
		assertThat(table.uniqueConstraints()[0].name()).isEqualTo("uq_application_acknowledgement_version");
		assertThat(table.uniqueConstraints()[0].columnNames()).containsExactly("application_id", "terms_version");
	}

	@Test
	@DisplayName("지원 ID·버전 0 이하, 시각 null, 조건 원문 null·공백 → IllegalArgumentException")
	void invalid() {
		assertThatThrownBy(() -> new ApplicationConditionAcknowledgement(0, 3, AT, "{}")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ApplicationConditionAcknowledgement(501, 0, AT, "{}")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ApplicationConditionAcknowledgement(501, 3, null, "{}")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ApplicationConditionAcknowledgement(501, 3, AT, null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ApplicationConditionAcknowledgement(501, 3, AT, " ")).isInstanceOf(IllegalArgumentException.class);
	}
}
