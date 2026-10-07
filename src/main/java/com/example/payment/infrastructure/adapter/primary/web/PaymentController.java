package com.example.payment.infrastructure.adapter.primary.web;

import com.example.payment.application.service.command.CreatePaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
public class PaymentController {

	// Episode 01: depends on the concrete service. Episode 02 inverts this onto CreatePaymentUseCase.
	private final CreatePaymentService createPaymentService;

	public PaymentController(CreatePaymentService createPaymentService) {
		this.createPaymentService = createPaymentService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public PaymentResponse create(@Valid @RequestBody PaymentRequest request) {
		return PaymentResponse.from(createPaymentService.createPayment(request.toCommand()));
	}

	// ponytail: domain rule violations -> 400; Episode 08 replaces this with application exceptions + a dedicated handler
	@ExceptionHandler(IllegalArgumentException.class)
	ProblemDetail invalidPayment(IllegalArgumentException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}
}
