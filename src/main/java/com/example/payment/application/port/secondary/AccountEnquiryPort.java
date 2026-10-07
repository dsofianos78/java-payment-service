package com.example.payment.application.port.secondary;

import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.AccountStatus;

import java.util.Optional;

/**
 * What the application needs to know about an account. The application owns
 * this contract; infrastructure decides where the answer comes from.
 */
public interface AccountEnquiryPort {

	/** The account's status, or empty if no such account exists. */
	Optional<AccountStatus> findStatus(AccountId accountId);
}
