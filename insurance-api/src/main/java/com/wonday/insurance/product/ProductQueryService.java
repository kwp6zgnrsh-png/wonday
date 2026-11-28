package com.wonday.insurance.product;

import com.wonday.insurance.api.ApiTime;
import java.time.OffsetDateTime;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ProductQueryService {

	private final ProductVersionRepository versions;
	private final ObjectMapper json;
	private final Clock productClock;

	List<ProductSummary> list() {
		return versions.findAvailableVersions(null, now()).stream().map(this::summary).toList();
	}

	ProductDetail detail(long productId) {
		ProductVersion version = versions.findAvailableVersions(productId, now()).stream().findFirst()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product is not available"));
		return new ProductDetail(summary(version), version.getTermsText(),
			json.readTree(version.getEligibleWorkGroups()), json.readTree(version.getStationSelectionRule()),
			json.readTree(version.getWindowRule()), json.readTree(version.getIndexRules()),
			json.readTree(version.getPayoutRules()));
	}

	private LocalDateTime now() {
		return LocalDateTime.ofInstant(productClock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
	}

	private ProductSummary summary(ProductVersion version) {
		InsuranceProduct product = version.getProduct();
		return new ProductSummary(product.getId(), product.getProductCode(), product.getName(),
			product.getDescription(), version.getId(), version.getVersionNo(),
			ApiTime.fromUtc(version.getSalesStartAt()), ApiTime.fromUtc(version.getSalesEndAt()), json.readTree(version.getPremiumTerms()));
	}

	record ProductSummary(long id, String productCode, String name, String description, long productVersionId,
		int versionNo, OffsetDateTime salesStartAt, OffsetDateTime salesEndAt, JsonNode premiumTerms) {}

	record ProductDetail(ProductSummary product, String termsText, JsonNode eligibleWorkGroups,
		JsonNode stationSelectionRule, JsonNode windowRule, JsonNode indexRules, JsonNode payoutRules) {}
}
