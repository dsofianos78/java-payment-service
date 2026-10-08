package com.example.payment.application.exception;

import com.example.payment.domain.valueobject.AccountId;

/** The account system answered, and it has no such account. */
public class AccountNotFoundException extends RuntimeException {

	public AccountNotFoundException(String role, AccountId accountId) {
		super(role + " account " + accountId + " does not exist");
	}
}
