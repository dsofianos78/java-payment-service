package com.example.payment.application.service.query;

import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.port.primary.GetRefundsUseCase;
import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.application.port.secondary.RefundRepository;
import com.example.payment.application.usecase.query.GetRefundsQuery;
import com.example.payment.application.validation.AccountStateValidator;
import com.example.payment.domain.entity.Refund;
import com.example.payment.domain.valueobject.PaymentId;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GetRefundsService implements GetRefundsUseCase {

	private final PaymentRepository paymentRepository;
	private final RefundRepository refundRepository;
	private final AccountStateValidator accountStateValidator;

	public GetRefundsService(PaymentRepository paymentRepository, RefundRepository refundRepository,
			AccountStateValidator accountStateValidator) {
		this.paymentRepository = paymentRepository;
		this.refundRepository = refundRepository;
		this.accountStateValidator = accountStateValidator;
	}

	@Override
	public List<Refund> getRefunds(GetRefundsQuery query) {
		PaymentId paymentId;
		try {
			paymentId = PaymentId.of(query.paymentId());
		}
		catch (IllegalArgumentException e) {
			throw new PaymentValidationException(e.getMessage(), e);
		}
		// The payment first, so another customer's payment is a 404, not an empty list.
		paymentRepository.findById(paymentId)
				.filter(p -> accountStateValidator.isHeldBy(p.sourceAccountId(), query.customerId()))
				.orElseThrow(() -> new PaymentNotFoundException(paymentId));
		return refundRepository.findByPaymentId(paymentId);
	}
}
