package com.wonday.insurance.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wonday.insurance.member.IdentityType;
import com.wonday.insurance.member.NationalityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class WorkerIdentityTests {

	private final ObjectMapper json = new ObjectMapper();

	@Test
	void bothNationalitiesUseTheCanonicalFieldsAndNormalizeWhitespace() {
		for (NationalityType nationality : NationalityType.values()) {
			WorkerIdentity identity = json.readValue("""
				{"name":" TEST WORKER ","phone":"010-0000-0000","nationalityType":"%s","identityNumber":"000000-0000000"}
				""".formatted(nationality), WorkerIdentity.class);
			assertThat(identity.name()).isEqualTo("TEST WORKER");
			assertThat(identity.phone()).isEqualTo("01000000000");
			assertThat(identity.identityNumber()).isEqualTo("0000000000000");
			assertThat(identity.identityType()).isEqualTo(nationality == NationalityType.DOMESTIC
				? IdentityType.RESIDENT_REGISTRATION_NUMBER : IdentityType.FOREIGN_REGISTRATION_NUMBER);
			assertThat(json.writeValueAsString(identity)).contains("\"identityNumber\":\"0000000000000\"")
				.doesNotContain("residentRegistrationNumber");
			assertThat(identity.toString()).doesNotContain("TEST WORKER", "01000000000", "0000000000000");
		}
		WorkerIdentity unicode = new WorkerIdentity(" TEST WORKER ", "010\u00a00000\t0000", NationalityType.FOREIGN, "000000\u00a00000000");
		assertThat(unicode.phone()).isEqualTo("01000000000");
		assertThat(unicode.identityNumber()).isEqualTo("0000000000000");
	}

	@Test
	void oldResidentNumberRequestsRemainDomestic() {
		WorkerIdentity identity = json.readValue("""
			{"name":"TEST","phone":"01000000000","residentRegistrationNumber":"000000-0000000"}
			""", WorkerIdentity.class);
		assertThat(identity.nationalityType()).isEqualTo(NationalityType.DOMESTIC);
		assertThat(identity.identityNumber()).isEqualTo("0000000000000");
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"\"identityNumber\":\"0000000000000\"",
		"\"nationalityType\":\"UNKNOWN\",\"identityNumber\":\"0000000000000\"",
		"\"nationalityType\":0,\"identityNumber\":\"0000000000000\"",
		"\"nationalityType\":null,\"identityNumber\":\"0000000000000\"",
		"\"nationalityType\":\"FOREIGN\",\"identityNumber\":9001015000000",
		"\"nationalityType\":\"FOREIGN\",\"identityNumber\":null",
		"\"nationalityType\":\"FOREIGN\",\"identityNumber\":\"wrong\"",
		"\"nationalityType\":\"FOREIGN\",\"residentRegistrationNumber\":\"0000000000000\"",
		"\"nationalityType\":\"DOMESTIC\",\"identityNumber\":\"0000000000000\",\"residentRegistrationNumber\":\"0000000000000\"",
		"\"nationalityType\":\"DOMESTIC\",\"identityNumber\":\"0000000000000\",\"residentRegistrationNumber\":null"
	})
	void missingAmbiguousAndNonStringIdentityFieldsAreRejected(String fields) {
		assertBadRequest("{\"name\":\"TEST\",\"phone\":\"01000000000\"," + fields + "}");
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"{\"name\":7,\"phone\":\"01000000000\",\"residentRegistrationNumber\":\"0000000000000\"}",
		"{\"name\":\"TEST\",\"phone\":10000000000,\"residentRegistrationNumber\":\"0000000000000\"}",
		"{\"name\":\"TEST\",\"phone\":\"01000000000\",\"residentRegistrationNumber\":9001015000000}",
		"[]", "\"identity\"", "{}"
	})
	void invalidIdentityShapesAndNumberCoercionAreRejected(String input) {
		assertBadRequest(input);
	}

	private void assertBadRequest(String input) {
		assertThatThrownBy(() -> json.readValue(input, WorkerIdentity.class)).satisfies(exception -> {
			Throwable root = exception;
			while (root.getCause() != null) root = root.getCause();
			assertThat(root).isInstanceOfSatisfying(ResponseStatusException.class,
				error -> assertThat(error.getStatusCode().value()).isEqualTo(400));
		});
	}
}
