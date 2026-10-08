package com.example.payment.infrastructure.adapter.secondary.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * The external account system's HTTP API, described in its own terms.
 * Feign generates the implementation; only {@link AccountEnquiryFeignAdapter} uses it.
 */
@FeignClient(name = "account-system", url = "${account-system.url}")
interface AccountClient {

	@GetMapping("/accounts/{accountNumber}")
	AccountResponse getAccount(@PathVariable String accountNumber);
}
