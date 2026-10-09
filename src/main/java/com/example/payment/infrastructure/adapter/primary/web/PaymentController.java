package com.example.payment.infrastructure.adapter.primary.web;

import com.example.payment.application.port.primary.CancelPaymentUseCase;
import com.example.payment.application.port.primary.CreatePaymentUseCase;
import com.example.payment.application.port.primary.GetPaymentUseCase;
import com.example.payment.application.port.primary.RequestPaymentExecutionUseCase;
import com.example.payment.application.usecase.command.CancelPaymentCommand;
import com.example.payment.application.usecase.command.ExecutePaymentCommand;
import com.example.payment.application.usecase.query.GetPaymentQuery;
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

@RestController
@RequestMapping("/payments")
public class PaymentController {

	// Depends on the primary ports, never on the services that implement them.
	private final CreatePaymentUseCase createPaymentUseCase;
	private final GetPaymentUseCase getPaymentUseCase;
	private final RequestPaymentExecutionUseCase requestPaymentExecutionUseCase;
	private final CancelPaymentUseCase cancelPaymentUseCase;

	public PaymentController(CreatePaymentUseCase createPaymentUseCase, GetPaymentUseCase getPaymentUseCase,
			RequestPaymentExecutionUseCase requestPaymentExecutionUseCase, CancelPaymentUseCase cancelPaymentUseCase) {
		this.createPaymentUseCase = createPaymentUseCase;
		this.getPaymentUseCase = getPaymentUseCase;
		this.requestPaymentExecutionUseCase = requestPaymentExecutionUseCase;
		this.cancelPaymentUseCase = cancelPaymentUseCase;
	}

	// A retry with the same Idempotency-Key gets the same payment back, still 201.
	// No header is a 400: a payment request the client can't safely retry is not accepted.
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public PaymentResponse create(@Valid @RequestBody PaymentRequest request,
			@RequestHeader("Idempotency-Key") String idempotencyKey) {
		return PaymentResponse.from(createPaymentUseCase.createPayment(request.toCommand(idempotencyKey)));
	}

	@GetMapping("/{paymentId}")
	public PaymentResponse get(@PathVariable String paymentId) {
		return PaymentResponse.from(getPaymentUseCase.getPayment(new GetPaymentQuery(paymentId)));
	}

	// 202: queued, not done. The body shows the payment as it is now (CREATED or AUTHORIZED); the caller follows
	// it with GET /payments/{id} until it is COMPLETED or FAILED.
	@PostMapping("/{paymentId}/execute")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public PaymentResponse execute(@PathVariable String paymentId) {
		return PaymentResponse.from(requestPaymentExecutionUseCase.requestExecution(new ExecutePaymentCommand(paymentId)));
	}

	// No status check here: whether a payment can still be cancelled is the domain's decision (409 if not).
	@PostMapping("/{paymentId}/cancel")
	public PaymentResponse cancel(@PathVariable String paymentId) {
		return PaymentResponse.from(cancelPaymentUseCase.cancelPayment(new CancelPaymentCommand(paymentId)));
	}
}
