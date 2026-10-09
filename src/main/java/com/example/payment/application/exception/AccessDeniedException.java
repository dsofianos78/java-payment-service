package com.example.payment.application.exception;

import com.example.payment.domain.valueobject.AccountId;

/**
 * The caller does not hold the account they want to pay from. Access control
 * (may this caller touch this account?), not payment authorization (may this
 * payment go ahead?, PaymentAuthorizationException).
 */
public class AccessDeniedException extends RuntimeException {

	public AccessDeniedException(AccountId accountId) {
		super("Source account " + accountId + " is not held by the caller");
	}
}
