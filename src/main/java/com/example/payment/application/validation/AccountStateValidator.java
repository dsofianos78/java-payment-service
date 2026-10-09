package com.example.payment.application.validation;

import com.example.payment.application.exception.AccessDeniedException;
import com.example.payment.application.exception.AccountNotFoundException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.port.secondary.AccountEnquiryPort;
import com.example.payment.application.port.secondary.AccountEnquiryPort.Account;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.AccountStatus;
import org.springframework.stereotype.Component;

/**
 * The caller holds the source account, and both accounts exist and are active.
 * Only the account system knows that, so this is not a domain invariant: the
 * answer comes through a secondary port.
 */
@Component
public class AccountStateValidator {

	private final AccountEnquiryPort accountEnquiryPort;

	public AccountStateValidator(AccountEnquiryPort accountEnquiryPort) {
		this.accountEnquiryPort = accountEnquiryPort;
	}

	public void validate(AccountId source, AccountId destination, String customerId) {
		Account sourceAccount = find(source, "Source");
		// Ownership before state: a caller who doesn't hold the account learns nothing about whether it is active.
		if (!isHeldBy(sourceAccount, customerId)) {
			throw new AccessDeniedException(source);
		}
		requireActive(sourceAccount, source, "Source");
		requireActive(find(destination, "Destination"), destination, "Destination");
	}

	/** Whether the customer holds the account. An unknown account is held by nobody. */
	public boolean isHeldBy(AccountId accountId, String customerId) {
		return accountEnquiryPort.findAccount(accountId).map(account -> isHeldBy(account, customerId)).orElse(false);
	}

	// False for a caller with no ID or an account with no holder: access is never granted by two blanks matching.
	private static boolean isHeldBy(Account account, String customerId) {
		return customerId != null && customerId.equals(account.holderId());
	}

	private Account find(AccountId accountId, String role) {
		return accountEnquiryPort.findAccount(accountId)
				.orElseThrow(() -> new AccountNotFoundException(role, accountId));
	}

	private static void requireActive(Account account, AccountId accountId, String role) {
		if (account.status() != AccountStatus.ACTIVE) {
			throw new PaymentValidationException(role + " account " + accountId + " is not active");
		}
	}
}
