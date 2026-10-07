package com.example.payment.infrastructure.adapter.secondary.stub;

import com.example.payment.application.port.secondary.AccountEnquiryPort;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.AccountStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

// ponytail: fixed fictional accounts; Episode 07 replaces this with a Feign adapter to an external account system
@Component
public class StubAccountEnquiryAdapter implements AccountEnquiryPort {

	private static final Map<String, AccountStatus> ACCOUNTS = Map.of(
			"ACC-10001", AccountStatus.ACTIVE,
			"ACC-20001", AccountStatus.ACTIVE,
			"ACC-30001", AccountStatus.ACTIVE,
			"ACC-80001", AccountStatus.BLOCKED,
			"ACC-90001", AccountStatus.CLOSED);

	@Override
	public Optional<AccountStatus> findStatus(AccountId accountId) {
		return Optional.ofNullable(ACCOUNTS.get(accountId.value()));
	}
}
