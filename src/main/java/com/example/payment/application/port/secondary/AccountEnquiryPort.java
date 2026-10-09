package com.example.payment.application.port.secondary;

import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.AccountStatus;

import java.util.Optional;

/**
 * What the application needs to know about an account. The application owns
 * this contract; infrastructure decides where the answer comes from.
 */
public interface AccountEnquiryPort {

	/** The account's status and holder, or empty if no such account exists. */
	Optional<Account> findAccount(AccountId accountId);

	/** @param holderId the customer who holds the account, the same ID a caller's token carries */
	record Account(AccountStatus status, String holderId) {
	}
}
