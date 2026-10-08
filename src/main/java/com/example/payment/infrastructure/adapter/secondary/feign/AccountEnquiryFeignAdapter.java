package com.example.payment.infrastructure.adapter.secondary.feign;

import com.example.payment.application.port.secondary.AccountEnquiryPort;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.AccountStatus;
import feign.FeignException;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Answers {@link AccountEnquiryPort} by asking the external account system,
 * and translates its vocabulary into ours. Nothing outside this package sees
 * {@link AccountResponse}, a Feign exception or the word "FROZEN".
 */
@Component
public class AccountEnquiryFeignAdapter implements AccountEnquiryPort {

	private final AccountClient accountClient;

	AccountEnquiryFeignAdapter(AccountClient accountClient) {
		this.accountClient = accountClient;
	}

	// ponytail: other HTTP errors and timeouts surface as FeignException -> 500; Episode 08 maps them to AccountUnavailableException, Episode 16 adds timeouts and retries
	@Override
	public Optional<AccountStatus> findStatus(AccountId accountId) {
		try {
			return Optional.of(toAccountStatus(accountClient.getAccount(accountId.value()).state()));
		}
		catch (FeignException.NotFound e) {
			return Optional.empty();
		}
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
