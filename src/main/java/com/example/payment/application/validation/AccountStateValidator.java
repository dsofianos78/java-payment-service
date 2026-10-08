package com.example.payment.application.validation;

import com.example.payment.application.port.secondary.AccountEnquiryPort;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.AccountStatus;
import org.springframework.stereotype.Component;

/**
 * Both accounts must exist and be active. Only the account system knows that,
 * so this is not a domain invariant: the answer comes through a secondary port.
 */
@Component
public class AccountStateValidator {

	private final AccountEnquiryPort accountEnquiryPort;

	public AccountStateValidator(AccountEnquiryPort accountEnquiryPort) {
		this.accountEnquiryPort = accountEnquiryPort;
	}

	public void validate(AccountId source, AccountId destination) {
		requireActive(source, "Source");
		requireActive(destination, "Destination");
	}

	// ponytail: IllegalArgumentException -> 400 for now; Episode 08 introduces AccountNotFoundException / AccountUnavailableException
	private void requireActive(AccountId accountId, String role) {
		AccountStatus status = accountEnquiryPort.findStatus(accountId)
				.orElseThrow(() -> new IllegalArgumentException(role + " account " + accountId + " does not exist"));
		if (status != AccountStatus.ACTIVE) {
			throw new IllegalArgumentException(role + " account " + accountId + " is not active");
		}
	}
}
