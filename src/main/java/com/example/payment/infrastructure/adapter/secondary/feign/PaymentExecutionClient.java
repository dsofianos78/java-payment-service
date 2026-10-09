package com.example.payment.infrastructure.adapter.secondary.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.math.BigDecimal;

/**
 * The external payment system's HTTP API, in its own terms.
 * Only {@link PaymentExecutionAdapter} uses it.
 */
@FeignClient(name = "payment-system", url = "${payment-system.url}")
interface PaymentExecutionClient {

	/** The payment system keeps the outcome per Idempotency-Key: sending the same key again can't pay twice. */
	@PostMapping("/payment-orders")
	PaymentOrderResponse submit(@RequestHeader("Idempotency-Key") String idempotencyKey,
			@RequestBody PaymentOrderRequest request);

	/** What became of the order sent with this Idempotency-Key. 404 if it never arrived. */
	@GetMapping("/payment-orders/{idempotencyKey}")
	PaymentOrderResponse find(@PathVariable String idempotencyKey);

	/** Sends money back for an order. Keyed by refund ID, so sending the same refund again can't refund twice. */
	@PostMapping("/refund-orders")
	PaymentOrderResponse refund(@RequestHeader("Idempotency-Key") String idempotencyKey,
			@RequestBody RefundOrderRequest request);

	record PaymentOrderRequest(String debtorAccount, String creditorAccount, BigDecimal amount, String currency,
			String reference) {
	}

	record RefundOrderRequest(String originalPaymentId, BigDecimal amount, String currency) {
	}

	/** {@code status} is SETTLED or REJECTED. */
	record PaymentOrderResponse(String status) {
	}
}
