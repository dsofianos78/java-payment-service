package com.example.payment.infrastructure.adapter.secondary.feign;

import com.example.payment.application.exception.AccountUnavailableException;
import com.example.payment.application.port.secondary.AccountEnquiryPort;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.AccountStatus;
import com.example.payment.infrastructure.observability.PaymentMetrics;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Answers {@link AccountEnquiryPort} by asking the external account system,
 * and translates its vocabulary into ours. Nothing outside this package sees
 * {@link AccountResponse}, a Feign exception or the word "FROZEN".
 */
@Component
public class AccountEnquiryFeignAdapter implements AccountEnquiryPort {

	private static final Logger log = LoggerFactory.getLogger(AccountEnquiryFeignAdapter.class);

	private final AccountClient accountClient;
	private final PaymentMetrics paymentMetrics;
	private final CircuitBreaker circuitBreaker;
	private final Retry retry;

	AccountEnquiryFeignAdapter(AccountClient accountClient, PaymentMetrics paymentMetrics,
			CircuitBreakerRegistry circuitBreakers, RetryRegistry retries) {
		this.accountClient = accountClient;
		this.paymentMetrics = paymentMetrics;
		this.circuitBreaker = circuitBreakers.circuitBreaker("account-system");
		this.retry = retries.retry("account-system");
	}

	// A read: asking again can't change anything, so a failed attempt is retried (application.properties).
	// Each attempt counts towards the circuit breaker; once it is open, nothing is retried or sent.
	@Override
	public Optional<Account> findAccount(AccountId accountId) {
		AccountResponse account;
		try {
			account = retry.executeSupplier(() -> circuitBreaker.executeSupplier(
					() -> accountClient.getAccount(accountId.value())));
		}
		catch (FeignException.NotFound e) {
			return Optional.empty();
		}
		// 5xx, timeout, connection refused: the account system gave no answer, which is not "no such account".
		// Only the status is logged: Feign's message holds the URL, which holds the account ID.
		catch (FeignException e) {
			log.warn("Account system unavailable (status {})", e.status());
			paymentMetrics.accountSystemError();
			throw new AccountUnavailableException(e);
		}
		catch (CallNotPermittedException e) {
			log.warn("Account system circuit breaker is open, not called");
			throw new AccountUnavailableException(e);
		}
		return Optional.of(new Account(toAccountStatus(account.state()), account.holderId()));
	}

	private static AccountStatus toAccountStatus(String state) {
		return switch (state) {
			case "OPEN" -> AccountStatus.ACTIVE;
			case "FROZEN" -> AccountStatus.BLOCKED;
			case "CLOSED" -> AccountStatus.CLOSED;
			// Fail closed: guessing could let money move from an account the bank has stopped.
			case null, default -> throw new IllegalStateException("Unknown account state from account system: " + state);
		};
	}
}
