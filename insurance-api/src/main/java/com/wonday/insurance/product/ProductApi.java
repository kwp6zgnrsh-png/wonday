package com.wonday.insurance.product;

import java.util.List;
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
	ProductQueryService.ProductDetail detail(@PathVariable long productId) {
		return products.detail(productId);
	}
}
