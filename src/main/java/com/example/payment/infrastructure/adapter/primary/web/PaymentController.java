package com.example.payment.infrastructure.adapter.primary.web;

import com.example.payment.application.port.primary.CreatePaymentUseCase;
import com.example.payment.application.port.primary.GetPaymentUseCase;
import com.example.payment.application.usecase.query.GetPaymentQuery;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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

	@GetMapping("/{paymentId}")
	public PaymentResponse get(@PathVariable String paymentId) {
		return PaymentResponse.from(getPaymentUseCase.getPayment(new GetPaymentQuery(paymentId)));
	}
}
