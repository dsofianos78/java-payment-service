package com.example.payment.application.service.query;

import com.example.payment.application.port.primary.GetPaymentUseCase;
import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.application.usecase.query.GetPaymentQuery;
import com.example.payment.application.usecase.query.GetPaymentResult;
import com.example.payment.domain.valueobject.PaymentId;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class GetPaymentService implements GetPaymentUseCase {

	private final PaymentRepository paymentRepository;

	public GetPaymentService(PaymentRepository paymentRepository) {
		this.paymentRepository = paymentRepository;
	}

	@Override
	public Optional<GetPaymentResult> getPayment(GetPaymentQuery query) {
		return paymentRepository.findById(PaymentId.of(query.paymentId())).map(GetPaymentResult::from);
	}
}
