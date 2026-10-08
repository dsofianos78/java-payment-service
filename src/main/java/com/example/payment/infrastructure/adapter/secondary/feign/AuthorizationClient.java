package com.example.payment.infrastructure.adapter.secondary.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.math.BigDecimal;

/**
 * The external authorization system's HTTP API, in its own terms.
 * Only {@link AuthorizationAdapter} uses it.
 */
@FeignClient(name = "authorization-system", url = "${authorization-system.url}")
interface AuthorizationClient {

	@PostMapping("/authorizations")
	AuthorizationResponse authorize(@RequestBody AuthorizationRequest request);

	record AuthorizationRequest(String paymentId, String debtorAccount, String creditorAccount,
			BigDecimal amount, String currency) {
	}

	/** {@code decision} is APPROVED or DECLINED. */
	record AuthorizationResponse(String decision) {
	}
}
