package com.example.payment.infrastructure.adapter.primary.web;

import com.example.payment.application.port.primary.CancelPaymentUseCase;
import com.example.payment.application.port.primary.CreatePaymentUseCase;
import com.example.payment.application.port.primary.GetPaymentUseCase;
import com.example.payment.application.port.primary.GetRefundsUseCase;
import com.example.payment.application.port.primary.RefundPaymentUseCase;
import com.example.payment.application.port.primary.RequestPaymentExecutionUseCase;
import com.example.payment.application.usecase.command.CancelPaymentCommand;
import com.example.payment.application.usecase.command.ExecutePaymentCommand;
import com.example.payment.application.usecase.query.GetPaymentQuery;
import com.example.payment.application.usecase.query.GetRefundsQuery;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/**
 * Every endpoint here needs a valid token (config/SecurityConfiguration). The
 * caller's customer ID comes from the token, as {@link Principal#getName()}, the
 * token's {@code sub}, and goes to the application as plain data on the command
 * or query. The request body never carries it: a client could put anyone's there.
 */
@RestController
@RequestMapping("/payments")
public class PaymentController {

	// Depends on the primary ports, never on the services that implement them.
	private final CreatePaymentUseCase createPaymentUseCase;
	private final GetPaymentUseCase getPaymentUseCase;
	private final RequestPaymentExecutionUseCase requestPaymentExecutionUseCase;
	private final CancelPaymentUseCase cancelPaymentUseCase;
	private final RefundPaymentUseCase refundPaymentUseCase;
	private final GetRefundsUseCase getRefundsUseCase;

	public PaymentController(CreatePaymentUseCase createPaymentUseCase, GetPaymentUseCase getPaymentUseCase,
			RequestPaymentExecutionUseCase requestPaymentExecutionUseCase, CancelPaymentUseCase cancelPaymentUseCase,
			RefundPaymentUseCase refundPaymentUseCase, GetRefundsUseCase getRefundsUseCase) {
		this.createPaymentUseCase = createPaymentUseCase;
		this.getPaymentUseCase = getPaymentUseCase;
		this.requestPaymentExecutionUseCase = requestPaymentExecutionUseCase;
		this.cancelPaymentUseCase = cancelPaymentUseCase;
		this.refundPaymentUseCase = refundPaymentUseCase;
		this.getRefundsUseCase = getRefundsUseCase;
	}

	// A retry with the same Idempotency-Key gets the same payment back, still 201.
	// No header is a 400: a payment request the client can't safely retry is not accepted.
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public PaymentResponse create(@Valid @RequestBody PaymentRequest request,
			@RequestHeader("Idempotency-Key") String idempotencyKey, Principal caller) {
		return PaymentResponse.from(createPaymentUseCase.createPayment(request.toCommand(idempotencyKey, caller.getName())));
	}

	@GetMapping("/{paymentId}")
	public PaymentResponse get(@PathVariable String paymentId, Principal caller) {
		return PaymentResponse.from(getPaymentUseCase.getPayment(new GetPaymentQuery(paymentId, caller.getName())));
	}

	// 202: queued, not done. The body shows the payment as it is now (CREATED or AUTHORIZED); the caller follows
	// it with GET /payments/{id} until it is COMPLETED or FAILED.
	@PostMapping("/{paymentId}/execute")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public PaymentResponse execute(@PathVariable String paymentId, Principal caller) {
		return PaymentResponse.from(requestPaymentExecutionUseCase.requestExecution(
				new ExecutePaymentCommand(paymentId, caller.getName())));
	}

	// No status check here: whether a payment can still be cancelled is the domain's decision (409 if not).
	@PostMapping("/{paymentId}/cancel")
	public PaymentResponse cancel(@PathVariable String paymentId, Principal caller) {
		return PaymentResponse.from(cancelPaymentUseCase.cancelPayment(new CancelPaymentCommand(paymentId, caller.getName())));
	}

	// 201 with the refund as it ended: COMPLETED, FAILED, or PROCESSING if the payment system's answer was lost.
	// Synchronous, unlike execute: one external call, and the customer is waiting for it. A retry with the same
	// Idempotency-Key gets the same refund back.
	@PostMapping("/{paymentId}/refunds")
	@ResponseStatus(HttpStatus.CREATED)
	public RefundResponse refund(@PathVariable String paymentId, @Valid @RequestBody RefundRequest request,
			@RequestHeader("Idempotency-Key") String idempotencyKey, Principal caller) {
		return RefundResponse.from(refundPaymentUseCase.refundPayment(
				request.toCommand(paymentId, caller.getName(), idempotencyKey)));
	}

	@GetMapping("/{paymentId}/refunds")
	public List<RefundResponse> refunds(@PathVariable String paymentId, Principal caller) {
		return getRefundsUseCase.getRefunds(new GetRefundsQuery(paymentId, caller.getName())).stream()
				.map(RefundResponse::from)
				.toList();
	}
}
