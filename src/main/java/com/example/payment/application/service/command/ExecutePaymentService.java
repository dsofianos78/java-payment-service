package com.example.payment.application.service.command;

import com.example.payment.application.exception.InvalidPaymentStateException;
import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.port.primary.ExecutePaymentUseCase;
import com.example.payment.application.port.secondary.PaymentExecutionPort;
import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.application.usecase.command.ExecutePaymentCommand;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.PaymentId;
import org.springframework.stereotype.Service;

@Service
public class ExecutePaymentService implements ExecutePaymentUseCase {

	private final PaymentRepository paymentRepository;
	private final PaymentExecutionPort paymentExecutionPort;

	public ExecutePaymentService(PaymentRepository paymentRepository, PaymentExecutionPort paymentExecutionPort) {
		this.paymentRepository = paymentRepository;
		this.paymentExecutionPort = paymentExecutionPort;
	}

	@Override
	public Payment executePayment(ExecutePaymentCommand command) {
		PaymentId paymentId;
		try {
			paymentId = PaymentId.of(command.paymentId());
		}
		catch (IllegalArgumentException e) {
			throw new PaymentValidationException(e.getMessage(), e);
		}
		Payment payment = paymentRepository.findById(paymentId)
				.orElseThrow(() -> new PaymentNotFoundException(paymentId));

		// The domain decides which moves are legal; the service only asks for them in order.
		try {
			// ponytail: every payment is authorized for now; Episode 12 puts PaymentAuthorizationPort in front of this
			payment.authorize();
			payment.startProcessing();
		}
		catch (IllegalStateException e) {
			throw new InvalidPaymentStateException(e.getMessage(), e);
		}
		// Stored before calling out: if we stop half-way, the payment reads PROCESSING, not CREATED,
		// so nobody executes it a second time.
		paymentRepository.save(payment);

		switch (paymentExecutionPort.execute(payment)) {
			case EXECUTED -> payment.complete();
			case REJECTED -> payment.fail();
		}
		paymentRepository.save(payment);
		return payment;
	}
}
