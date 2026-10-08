package com.example.payment.infrastructure.adapter.secondary.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.math.BigDecimal;

/**
 * The external limit system's HTTP API, in its own terms.
 * Only {@link LimitAdapter} uses it.
 */
@FeignClient(name = "limit-system", url = "${limit-system.url}")
interface LimitClient {

	@PostMapping("/limit-checks")
	LimitCheckResponse check(@RequestBody LimitCheckRequest request);

	record LimitCheckRequest(String accountNumber, BigDecimal amount, String currency) {
	}

	/** {@code result} is WITHIN_LIMIT or LIMIT_EXCEEDED. */
	record LimitCheckResponse(String result) {
	}
}
