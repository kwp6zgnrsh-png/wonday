package com.wonday.insurance.product;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ProductEnrollmentCatalog {

	private final ProductVersionRepository versions;

	@Transactional(readOnly = true)
	public AvailableProduct requireAvailable(long versionId, LocalDateTime now) {
		ProductVersion requested = versions.findById(versionId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product version does not exist"));
		ProductVersion current = versions.findAvailableVersions(requested.getProduct().getId(), now)
			.stream().findFirst()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Product is not on sale"));
		if (!current.getId().equals(requested.getId())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Product version has changed");
		}
		return new AvailableProduct(current.getProduct().getId(), current.getId());
	}

	public record AvailableProduct(long productId, long versionId) {}
}
