package com.wonday.insurance.product;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
class ProductApi {

	private final ProductQueryService products;

	@GetMapping
	List<ProductQueryService.ProductSummary> list() {
		return products.list();
	}

	@GetMapping("/{productId}")
	ProductQueryService.ProductDetail detail(@PathVariable String productId) {
		if (!productId.matches("[1-9][0-9]*")) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Product ID must be a positive decimal integer");
		}
		try {
			return products.detail(Long.parseLong(productId));
		} catch (NumberFormatException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Product ID exceeds the supported range");
		}
	}
}
