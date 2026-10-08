package com.example.payment.application.service.command;

import com.example.payment.application.exception.InvalidPaymentStateException;
import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.port.primary.CancelPaymentUseCase;
import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.application.usecase.command.CancelPaymentCommand;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.PaymentId;
import org.springframework.stereotype.Service;

@Service
public class CancelPaymentService implements CancelPaymentUseCase {

	private final PaymentRepository paymentRepository;

	public CancelPaymentService(PaymentRepository paymentRepository) {
		this.paymentRepository = paymentRepository;
	}

	@Override
	public Payment cancelPayment(CancelPaymentCommand command) {
		PaymentId paymentId;
		try {
			paymentId = PaymentId.of(command.paymentId());
		}
		catch (IllegalArgumentException e) {
			throw new PaymentValidationException(e.getMessage(), e);
		}
		Payment payment = paymentRepository.findById(paymentId)
				.orElseThrow(() -> new PaymentNotFoundException(paymentId));

		// The service doesn't check the status itself: Payment knows which states can still be cancelled.
		try {
			payment.cancel();
		}
		catch (IllegalStateException e) {
			throw new InvalidPaymentStateException(e.getMessage(), e);
		}
		paymentRepository.save(payment);
		return payment;
	}
}
