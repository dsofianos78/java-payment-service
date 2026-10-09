package com.example.payment.application.port.primary;

import com.example.payment.application.usecase.query.GetRefundsQuery;
import com.example.payment.domain.entity.Refund;

import java.util.List;

public interface GetRefundsUseCase {

	/** The payment's refunds, oldest first. */
	List<Refund> getRefunds(GetRefundsQuery query);
}
