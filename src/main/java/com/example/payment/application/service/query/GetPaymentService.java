package com.example.payment.application.service.query;

import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.port.primary.GetPaymentUseCase;
import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.application.usecase.query.GetPaymentQuery;
import com.example.payment.application.usecase.query.GetPaymentResult;
import com.example.payment.domain.valueobject.PaymentId;
import org.springframework.stereotype.Service;

@Service
public class GetPaymentService implements GetPaymentUseCase {

	private final PaymentRepository paymentRepository;

	public GetPaymentService(PaymentRepository paymentRepository) {
		this.paymentRepository = paymentRepository;
	}

	@Override
	public GetPaymentResult getPayment(GetPaymentQuery query) {
		PaymentId paymentId;
		try {
			paymentId = PaymentId.of(query.paymentId());
		}
		catch (IllegalArgumentException e) {
			throw new PaymentValidationException(e.getMessage(), e);
		}
		return paymentRepository.findById(paymentId)
				.map(GetPaymentResult::from)
				.orElseThrow(() -> new PaymentNotFoundException(paymentId));
	}
}
