package com.example.payment.infrastructure.adapter.primary.web;

import com.example.payment.application.port.primary.CreatePaymentUseCase;
import com.example.payment.application.port.primary.GetPaymentUseCase;
import com.example.payment.application.usecase.query.GetPaymentQuery;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
public class PaymentController {

	// Depends on the primary ports, never on the services that implement them.
	private final CreatePaymentUseCase createPaymentUseCase;
	private final GetPaymentUseCase getPaymentUseCase;

	public PaymentController(CreatePaymentUseCase createPaymentUseCase, GetPaymentUseCase getPaymentUseCase) {
		this.createPaymentUseCase = createPaymentUseCase;
		this.getPaymentUseCase = getPaymentUseCase;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public PaymentResponse create(@Valid @RequestBody PaymentRequest request) {
		return PaymentResponse.from(createPaymentUseCase.createPayment(request.toCommand()));
	}

	// ponytail: unknown id -> bare 404; Episode 08 introduces PaymentNotFoundException and a problem-details body
	@GetMapping("/{paymentId}")
	public ResponseEntity<PaymentResponse> get(@PathVariable String paymentId) {
		return ResponseEntity.of(getPaymentUseCase.getPayment(new GetPaymentQuery(paymentId)).map(PaymentResponse::from));
	}

	// ponytail: domain rule violations -> 400; Episode 08 replaces this with application exceptions + a dedicated handler
	@ExceptionHandler(IllegalArgumentException.class)
	ProblemDetail invalidPayment(IllegalArgumentException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}
}
