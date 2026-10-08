package com.example.payment.infrastructure.adapter.secondary.feign;

import com.example.payment.application.exception.ExternalSystemUnavailableException;
import com.example.payment.application.port.secondary.PaymentLimitPort;
import com.example.payment.domain.entity.Payment;
import com.example.payment.infrastructure.adapter.secondary.feign.LimitClient.LimitCheckRequest;
import feign.FeignException;
import org.springframework.stereotype.Component;

/**
 * Answers {@link PaymentLimitPort} by asking the external limit system
 * about the source account.
 */
@Component
public class LimitAdapter implements PaymentLimitPort {

	private final LimitClient limitClient;

	LimitAdapter(LimitClient limitClient) {
		this.limitClient = limitClient;
	}

	// ponytail: Feign default timeouts, no retries; Episode 16 adds them
	@Override
	public boolean isWithinLimit(Payment payment) {
		String result;
		try {
			result = limitClient.check(new LimitCheckRequest(payment.sourceAccountId().value(),
					payment.amount().amount(), payment.amount().currency().name())).result();
		}
		catch (FeignException e) {
			throw new ExternalSystemUnavailableException("Limit system", e);
		}
		return switch (result) {
			case "WITHIN_LIMIT" -> true;
			case "LIMIT_EXCEEDED" -> false;
			// Fail closed: an answer we don't understand is not a yes.
			case null, default -> throw new IllegalStateException("Unknown result from limit system: " + result);
		};
	}
}
