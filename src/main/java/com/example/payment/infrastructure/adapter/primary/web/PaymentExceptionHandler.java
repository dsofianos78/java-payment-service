package com.example.payment.infrastructure.adapter.primary.web;

import com.example.payment.application.exception.AccountNotFoundException;
import com.example.payment.application.exception.AccountUnavailableException;
import com.example.payment.application.exception.IdempotencyKeyInProgressException;
import com.example.payment.application.exception.IdempotencyKeyMismatchException;
import com.example.payment.application.exception.InvalidPaymentStateException;
import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.exception.PaymentValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The only place that knows which application exception is which HTTP status.
 * The application says what went wrong; HTTP is how this adapter reports it.
 */
@RestControllerAdvice
class PaymentExceptionHandler {

	// An unknown account is wrong input to a payment, not a missing /payments resource: 400, not 404.
	@ExceptionHandler({PaymentValidationException.class, AccountNotFoundException.class})
	ProblemDetail badRequest(RuntimeException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	@ExceptionHandler(PaymentNotFoundException.class)
	ProblemDetail notFound(PaymentNotFoundException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}

	// The request is fine and the payment exists; it is just in the wrong state for this action.
	// Same for a key whose first request hasn't finished: retry later.
	@ExceptionHandler({InvalidPaymentStateException.class, IdempotencyKeyInProgressException.class})
	ProblemDetail conflict(RuntimeException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
	}

	// Well-formed, but the key belongs to another request; retrying the same thing can never succeed.
	@ExceptionHandler(IdempotencyKeyMismatchException.class)
	ProblemDetail unprocessable(IdempotencyKeyMismatchException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
	}

	// The cause (a Feign exception) is not shown: the caller only needs to know to retry later.
	@ExceptionHandler(AccountUnavailableException.class)
	ProblemDetail serviceUnavailable(AccountUnavailableException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
	}
}
