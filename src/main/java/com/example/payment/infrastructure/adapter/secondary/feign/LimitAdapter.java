package com.example.payment.infrastructure.adapter.secondary.feign;

import com.example.payment.application.exception.ExternalSystemUnavailableException;
import com.example.payment.application.port.secondary.PaymentLimitPort;
import com.example.payment.domain.entity.Payment;
import com.example.payment.infrastructure.observability.PaymentMetrics;
import com.example.payment.infrastructure.adapter.secondary.feign.LimitClient.LimitCheckRequest;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Answers {@link PaymentLimitPort} by asking the external limit system
 * about the source account.
 */
@Component
public class LimitAdapter implements PaymentLimitPort {

	private static final Logger log = LoggerFactory.getLogger(LimitAdapter.class);

	private final LimitClient limitClient;
	private final PaymentMetrics paymentMetrics;
	private final CircuitBreaker circuitBreaker;
	private final Retry retry;

	LimitAdapter(LimitClient limitClient, PaymentMetrics paymentMetrics, CircuitBreakerRegistry circuitBreakers,
			RetryRegistry retries) {
		this.limitClient = limitClient;
		this.paymentMetrics = paymentMetrics;
		this.circuitBreaker = circuitBreakers.circuitBreaker("limit-system");
		this.retry = retries.retry("limit-system");
	}

	// A question, not an instruction: asking twice is harmless, so a failed attempt is retried.
	@Override
	public boolean isWithinLimit(Payment payment) {
		String result;
		LimitCheckRequest request = new LimitCheckRequest(payment.sourceAccountId().value(),
				payment.amount().amount(), payment.amount().currency().name());
		try {
			result = retry.executeSupplier(() -> circuitBreaker.executeSupplier(
					() -> limitClient.check(request))).result();
		}
		catch (FeignException e) {
			log.warn("Limit system unavailable (status {})", e.status());
			paymentMetrics.paymentSystemError("limit");
			throw new ExternalSystemUnavailableException("Limit system", e);
		}
		catch (CallNotPermittedException e) {
			log.warn("Limit system circuit breaker is open, not called");
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
