package com.wonday.insurance.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ProductApiTests.FixedTime.class)
@Transactional
class ProductApiTests {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 0, 0);

	@Autowired
	private MockMvc mvc;

	@Autowired
	private EntityManager entities;

	@Test
	void emptyCatalogReturnsAnEmptyList() throws Exception {
		mvc.perform(get("/api/products")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void catalogSelectsLatestVersionWithinItsSalesWindow() throws Exception {
		InsuranceProduct product = product("ACTIVE");
		version(product, 1, NOW.minusDays(2), null);
		ProductVersion current = version(product, 2, NOW.minusDays(1), NOW.plusDays(1));
		version(product, 3, NOW.plusSeconds(1), null);
		version(product, 4, NOW.minusDays(1), NOW);
		InsuranceProduct second = product("ACTIVE");
		version(second, 1, NOW, null);
		flush();

		mvc.perform(get("/api/products"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].id").value(product.getId().intValue()))
			.andExpect(jsonPath("$[0].productVersionId").value(current.getId().intValue()))
			.andExpect(jsonPath("$[0].versionNo").value(2))
			.andExpect(jsonPath("$[0].premiumTerms.amount").value(5000))
			.andExpect(jsonPath("$[0].termsText").doesNotExist())
			.andExpect(jsonPath("$[1].id").value(second.getId().intValue()));
	}

	@Test
	void detailIncludesReadableTermsAndStructuredJsonRules() throws Exception {
		InsuranceProduct product = product("ACTIVE");
		ProductVersion current = version(product, 1, NOW, null);
		flush();

		mvc.perform(get("/api/products/{id}", product.getId()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.product.productVersionId").value(current.getId().intValue()))
			.andExpect(jsonPath("$.product.name").value("농업 소득 보장"))
			.andExpect(jsonPath("$.termsText").value("시연용 약관"))
			.andExpect(jsonPath("$.eligibleWorkGroups[0]").value("HARVEST"))
			.andExpect(jsonPath("$.stationSelectionRule.method").value("NEAREST"))
			.andExpect(jsonPath("$.windowRule.type").value("WORK_HOURS"))
			.andExpect(jsonPath("$.indexRules.version").value("test-v1"))
			.andExpect(jsonPath("$.payoutRules.threshold").value(70));
	}

	@Test
	void unavailableProductsAreHiddenFromBothListAndDetail() throws Exception {
		InsuranceProduct draft = product("DRAFT");
		version(draft, 1, NOW.minusDays(1), null);
		InsuranceProduct ended = product("CLOSED");
		version(ended, 1, NOW.minusDays(1), null);
		InsuranceProduct future = product("ACTIVE");
		version(future, 1, NOW.plusSeconds(1), null);
		InsuranceProduct expired = product("ACTIVE");
		version(expired, 1, NOW.minusDays(1), NOW);
		InsuranceProduct noVersion = product("ACTIVE");
		flush();

		mvc.perform(get("/api/products")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
		for (InsuranceProduct product : new InsuranceProduct[]{draft, ended, future, expired, noVersion}) {
			mvc.perform(get("/api/products/{id}", product.getId())).andExpect(status().isNotFound());
		}
	}

	@Test
	void unknownAndInvalidProductIdsHaveExplicitStatuses() throws Exception {
		mvc.perform(get("/api/products/9223372036854775807")).andExpect(status().isNotFound());
		mvc.perform(get("/api/products/not-a-number")).andExpect(status().isBadRequest());
	}

	@Test
	void catalogReadsDoNotEnableAnonymousWrites() throws Exception {
		mvc.perform(post("/api/products")).andExpect(status().isForbidden());
	}

	private InsuranceProduct product(String status) {
		InsuranceProduct product = new InsuranceProduct();
		ReflectionTestUtils.setField(product, "productCode", "test-" + UUID.randomUUID());
		ReflectionTestUtils.setField(product, "name", "농업 소득 보장");
		ReflectionTestUtils.setField(product, "description", "시연용 상품 설명");
		ReflectionTestUtils.setField(product, "status", status);
		entities.persist(product);
		return product;
	}

	private ProductVersion version(InsuranceProduct product, int number, LocalDateTime start, LocalDateTime end) {
		ProductVersion version = new ProductVersion();
		ReflectionTestUtils.setField(version, "product", product);
		ReflectionTestUtils.setField(version, "versionNo", number);
		ReflectionTestUtils.setField(version, "salesStartAt", start);
		ReflectionTestUtils.setField(version, "salesEndAt", end);
		ReflectionTestUtils.setField(version, "termsText", "시연용 약관");
		ReflectionTestUtils.setField(version, "premiumTerms", "{\"amount\":5000,\"currency\":\"KRW\"}");
		ReflectionTestUtils.setField(version, "eligibleWorkGroups", "[\"HARVEST\"]");
		ReflectionTestUtils.setField(version, "stationSelectionRule", "{\"method\":\"NEAREST\"}");
		ReflectionTestUtils.setField(version, "windowRule", "{\"type\":\"WORK_HOURS\"}");
		ReflectionTestUtils.setField(version, "indexRules", "{\"version\":\"test-v1\"}");
		ReflectionTestUtils.setField(version, "payoutRules", "{\"threshold\":70}");
		entities.persist(version);
		return version;
	}

	private void flush() {
		entities.flush();
		entities.clear();
	}

	@TestConfiguration
	static class FixedTime {

		@Bean
		@Primary
		Clock fixedProductClock() {
			return Clock.fixed(Instant.parse("2026-11-24T00:00:00Z"), ZoneOffset.UTC);
		}
	}
}
