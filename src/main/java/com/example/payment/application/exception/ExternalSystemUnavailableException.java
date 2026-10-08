package com.example.payment.application.exception;

/**
 * An external system the payment depends on could not answer. Nothing was
 * decided, so the caller should try again later.
 */
public class ExternalSystemUnavailableException extends RuntimeException {

	public ExternalSystemUnavailableException(String system, Throwable cause) {
		super(system + " is unavailable", cause);
	}
}
