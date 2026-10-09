package com.example.payment.infrastructure.adapter.secondary.feign;

import com.example.payment.application.exception.ExternalSystemUnavailableException;
import com.example.payment.application.port.secondary.PaymentAuthorizationPort;
import com.example.payment.domain.entity.Payment;
import com.example.payment.infrastructure.observability.PaymentMetrics;
import com.example.payment.infrastructure.adapter.secondary.feign.AuthorizationClient.AuthorizationRequest;
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
 * Answers {@link PaymentAuthorizationPort} by asking the external
 * authorization system. It translates, it doesn't decide: the decision is
 * theirs.
 */
@Component
public class AuthorizationAdapter implements PaymentAuthorizationPort {

	private static final Logger log = LoggerFactory.getLogger(AuthorizationAdapter.class);

	private final AuthorizationClient authorizationClient;
	private final PaymentMetrics paymentMetrics;
	private final CircuitBreaker circuitBreaker;
	private final Retry retry;

	AuthorizationAdapter(AuthorizationClient authorizationClient, PaymentMetrics paymentMetrics, CircuitBreakerRegistry circuitBreakers,
			RetryRegistry retries) {
		this.authorizationClient = authorizationClient;
		this.paymentMetrics = paymentMetrics;
		this.circuitBreaker = circuitBreakers.circuitBreaker("authorization-system");
		this.retry = retries.retry("authorization-system");
	}

	// A question, not an instruction: asking twice is harmless, so a failed attempt is retried.
	@Override
	public boolean isAuthorized(Payment payment) {
		String decision;
		AuthorizationRequest request = new AuthorizationRequest(payment.id().toString(),
				payment.sourceAccountId().value(), payment.destinationAccountId().value(),
				payment.amount().amount(), payment.amount().currency().name());
		try {
			decision = retry.executeSupplier(() -> circuitBreaker.executeSupplier(
					() -> authorizationClient.authorize(request))).decision();
		}
		catch (FeignException e) {
			log.warn("Authorization system unavailable (status {})", e.status());
			paymentMetrics.paymentSystemError("authorization");
			throw new ExternalSystemUnavailableException("Authorization system", e);
		}
		catch (CallNotPermittedException e) {
			log.warn("Authorization system circuit breaker is open, not called");
			throw new ExternalSystemUnavailableException("Authorization system", e);
		}
		return switch (decision) {
			case "APPROVED" -> true;
			case "DECLINED" -> false;
			// Fail closed: an answer we don't understand is not a yes.
			case null, default -> throw new IllegalStateException("Unknown decision from authorization system: " + decision);
		};
	}
}
