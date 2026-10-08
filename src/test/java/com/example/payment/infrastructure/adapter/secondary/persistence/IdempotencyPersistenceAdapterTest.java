package com.example.payment.infrastructure.adapter.secondary.persistence;

import com.example.payment.TestcontainersConfiguration;
import com.example.payment.application.port.secondary.IdempotencyPort.StoredRequest;
import com.example.payment.domain.valueobject.PaymentId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/** Against real PostgreSQL, since ON CONFLICT and the primary key are what make the claim atomic. */
@DataJpaTest
@Import({TestcontainersConfiguration.class, IdempotencyPersistenceAdapter.class})
class IdempotencyPersistenceAdapterTest {

	private static final String FINGERPRINT_A = "a".repeat(64);
	private static final String FINGERPRINT_B = "b".repeat(64);

	@Autowired
	IdempotencyPersistenceAdapter adapter;

	@Test
	void firstClaimWinsAndIsReadBack() {
		PaymentId paymentId = PaymentId.newId();

		assertThat(adapter.claim("key-1", FINGERPRINT_A, paymentId)).isTrue();

		assertThat(adapter.find("key-1")).contains(new StoredRequest(FINGERPRINT_A, paymentId));
	}

	@Test
	void secondClaimOfTheSameKeyLosesAndChangesNothing() {
		PaymentId first = PaymentId.newId();
		adapter.claim("key-1", FINGERPRINT_A, first);

		assertThat(adapter.claim("key-1", FINGERPRINT_B, PaymentId.newId())).isFalse();

		assertThat(adapter.find("key-1")).contains(new StoredRequest(FINGERPRINT_A, first));
	}

	@Test
	void findsNothingForAnUnusedKey() {
		assertThat(adapter.find("never-used")).isEmpty();
	}
}
