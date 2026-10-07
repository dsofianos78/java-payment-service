package com.example.payment.infrastructure.adapter.secondary.persistence;

import com.example.payment.TestcontainersConfiguration;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs against real PostgreSQL with the Flyway schema, so it proves the
 * entity, the mapping and the migration agree.
 */
@DataJpaTest
@Import({TestcontainersConfiguration.class, PaymentPersistenceAdapter.class})
class PaymentPersistenceAdapterTest {

	@Autowired
	PaymentPersistenceAdapter adapter;

	@Autowired
	TestEntityManager entityManager;

	@Test
	void storesEveryFieldOfThePayment() {
		Payment payment = Payment.create(new AccountId("ACC-10001"), new AccountId("ACC-20001"),
				new Money(new BigDecimal("250.00"), Currency.EUR), new PaymentReference("Invoice 12345"));

		adapter.save(payment);
		entityManager.flush();
		entityManager.clear(); // read back from the table, not from Hibernate's cache

		PaymentEntity row = entityManager.find(PaymentEntity.class, payment.id().value());
		assertThat(row.getSourceAccountId()).isEqualTo("ACC-10001");
		assertThat(row.getDestinationAccountId()).isEqualTo("ACC-20001");
		assertThat(row.getAmount()).isEqualByComparingTo("250.00");
		assertThat(row.getCurrency()).isEqualTo("EUR");
		assertThat(row.getReference()).isEqualTo("Invoice 12345");
		assertThat(row.getStatus()).isEqualTo("CREATED");
	}
}
