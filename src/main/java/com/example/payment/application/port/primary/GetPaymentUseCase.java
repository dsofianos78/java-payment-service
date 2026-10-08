package com.example.payment.application.port.primary;

import com.example.payment.application.usecase.query.GetPaymentQuery;
import com.example.payment.application.usecase.query.GetPaymentResult;

public interface GetPaymentUseCase {

	/** @throws com.example.payment.application.exception.PaymentNotFoundException if no payment has that id */
	GetPaymentResult getPayment(GetPaymentQuery query);
}
