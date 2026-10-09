package com.example.payment.infrastructure.adapter.secondary.feign;

import com.example.payment.application.exception.ExternalSystemUnavailableException;
import com.example.payment.application.port.secondary.PaymentAuthorizationPort;
import com.example.payment.domain.entity.Payment;
import com.example.payment.infrastructure.observability.PaymentMetrics;
import com.example.payment.infrastructure.adapter.secondary.feign.AuthorizationClient.AuthorizationRequest;
import feign.FeignException;
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

	AuthorizationAdapter(AuthorizationClient authorizationClient, PaymentMetrics paymentMetrics) {
		this.authorizationClient = authorizationClient;
		this.paymentMetrics = paymentMetrics;
	}

	// ponytail: Feign default timeouts, no retries; Episode 16 adds them
	@Override
	public boolean isAuthorized(Payment payment) {
		String decision;
		try {
			decision = authorizationClient.authorize(new AuthorizationRequest(payment.id().toString(),
					payment.sourceAccountId().value(), payment.destinationAccountId().value(),
					payment.amount().amount(), payment.amount().currency().name())).decision();
		}
		catch (FeignException e) {
			log.warn("Authorization system unavailable (status {})", e.status());
			paymentMetrics.paymentSystemError("authorization");
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
