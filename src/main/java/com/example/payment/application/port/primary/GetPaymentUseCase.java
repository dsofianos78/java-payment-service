package com.example.payment.application.port.primary;

import com.example.payment.application.usecase.query.GetPaymentQuery;
import com.example.payment.application.usecase.query.GetPaymentResult;

import java.util.Optional;

public interface GetPaymentUseCase {

	/** The payment, or empty if no payment has that id. */
	Optional<GetPaymentResult> getPayment(GetPaymentQuery query);
}
