package com.example.payment.application.exception;

/**
 * The account system could not answer. Nothing is known about the account,
 * so the payment is neither valid nor invalid: try again later.
 */
public class AccountUnavailableException extends RuntimeException {

	public AccountUnavailableException(Throwable cause) {
		super("Account system is unavailable", cause);
	}
}
