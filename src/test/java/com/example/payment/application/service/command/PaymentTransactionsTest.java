package com.example.payment.application.service.command;

import com.example.payment.TestcontainersConfiguration;
import com.example.payment.application.exception.InvalidPaymentStateException;
import com.example.payment.application.port.primary.CreatePaymentUseCase;
import com.example.payment.application.port.primary.ExecutePaymentUseCase;
import com.example.payment.application.usecase.command.CreatePaymentCommand;
import com.example.payment.application.usecase.command.ExecutePaymentCommand;
import com.example.payment.domain.entity.Payment;
import com.example.payment.infrastructure.adapter.secondary.persistence.AuditPersistenceAdapter;
import com.example.payment.infrastructure.adapter.secondary.persistence.PaymentPersistenceAdapter;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.example.payment.domain.valueobject.PaymentStatus.AUTHORIZED;
import static com.example.payment.domain.valueobject.PaymentStatus.COMPLETED;
import static com.example.payment.domain.valueobject.PaymentStatus.CREATED;
import static com.example.payment.domain.valueobject.PaymentStatus.PROCESSING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatRuntimeException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

/**
 * The application services against real PostgreSQL, with one database write
 * made to fail on purpose, to show what each transaction takes back with it
 * and what it can't.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PaymentTransactionsTest {

	@RegisterExtension
	static WireMockExtension externalSystems = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort().usingFilesUnderDirectory("wiremock"))
			.build();

	@DynamicPropertySource
	static void externalSystemUrls(DynamicPropertyRegistry registry) {
		registry.add("account-system.url", externalSystems::baseUrl);
		registry.add("authorization-system.url", externalSystems::baseUrl);
		registry.add("limit-system.url", externalSystems::baseUrl);
	}

	@Autowired
	CreatePaymentUseCase createPayment;

	@Autowired
	ExecutePaymentUseCase executePayment;

	@Autowired
	JdbcClient jdbc;

	@MockitoSpyBean
	AuditPersistenceAdapter audit;

	@MockitoSpyBean
	PaymentPersistenceAdapter payments;

	@Test
	void everyStatusChangeIsAudited() {
		Payment payment = create(UUID.randomUUID().toString());

		executePayment.executePayment(execute(payment));

		assertThat(storedStatus(payment)).isEqualTo("COMPLETED");
		assertThat(auditTrail(payment)).containsExactly(
				"CREATED->AUTHORIZED", "AUTHORIZED->PROCESSING", "PROCESSING->COMPLETED");
	}

	@Test
	void aFailedAuditWriteRollsBackTheStatusChange() {
		Payment payment = create(UUID.randomUUID().toString());
		doThrow(new RuntimeException("audit store down")).when(audit).recordTransition(any(), eq(CREATED), eq(AUTHORIZED));

		assertThatRuntimeException().isThrownBy(() -> executePayment.executePayment(execute(payment)))
				.withMessage("audit store down");

		// The status was written first, then rolled back with the audit row: neither exists without the other.
		assertThat(storedStatus(payment)).isEqualTo("CREATED");
		assertThat(auditTrail(payment)).isEmpty();
	}

	@Test
	void moneyThatMovedIsNotRolledBack() {
		Payment payment = create(UUID.randomUUID().toString());
		doThrow(new RuntimeException("audit store down")).when(audit).recordTransition(any(), eq(PROCESSING), eq(COMPLETED));

		assertThatRuntimeException().isThrownBy(() -> executePayment.executePayment(execute(payment)));

		// The payment system already executed it. Our rollback only takes back COMPLETED, so the payment
		// stays PROCESSING: "sent, outcome not recorded". It can't be executed again.
		assertThat(storedStatus(payment)).isEqualTo("PROCESSING");
		assertThat(auditTrail(payment)).containsExactly("CREATED->AUTHORIZED", "AUTHORIZED->PROCESSING");
	}

	@Test
	void aFailedSaveReleasesTheIdempotencyKey() {
		String key = UUID.randomUUID().toString();
		doThrow(new RuntimeException("payment store down")).doCallRealMethod().when(payments).save(any());

		assertThatRuntimeException().isThrownBy(() -> create(key)).withMessage("payment store down");
		assertThat(jdbc.sql("SELECT count(*) FROM idempotency_key WHERE idempotency_key = ?").param(key)
				.query(Integer.class).single()).isZero();

		// The claim was rolled back with the save, so the retry is a fresh request, not a 409.
		Payment retried = create(key);
		assertThat(storedStatus(retried)).isEqualTo("CREATED");
	}

	@Test
	void concurrentExecutesSendThePaymentOnce() throws Exception {
		Payment payment = create(UUID.randomUUID().toString());

		List<Callable<Payment>> requests = Collections.nCopies(8, () -> executePayment.executePayment(execute(payment)));
		int succeeded = 0;
		List<Throwable> refused = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
			for (Future<Payment> f : pool.invokeAll(requests)) {
				try {
					f.get();
					succeeded++;
				}
				catch (ExecutionException e) {
					refused.add(e.getCause());
				}
			}
		}

		assertThat(succeeded).isEqualTo(1);
		assertThat(refused).hasSize(7).allMatch(InvalidPaymentStateException.class::isInstance);
		assertThat(auditTrail(payment)).containsExactly(
				"CREATED->AUTHORIZED", "AUTHORIZED->PROCESSING", "PROCESSING->COMPLETED");
	}

	private Payment create(String idempotencyKey) {
		return createPayment.createPayment(new CreatePaymentCommand("ACC-10001", "ACC-20001",
				new BigDecimal("250.00"), "EUR", "Invoice 13013", idempotencyKey));
	}

	private static ExecutePaymentCommand execute(Payment payment) {
		return new ExecutePaymentCommand(payment.id().toString());
	}

	private String storedStatus(Payment payment) {
		return jdbc.sql("SELECT status FROM payment WHERE id = ?").param(payment.id().value())
				.query(String.class).single();
	}

	private List<String> auditTrail(Payment payment) {
		return jdbc.sql("SELECT from_status || '->' || to_status FROM payment_audit WHERE payment_id = ? ORDER BY id")
				.param(payment.id().value()).query(String.class).list();
	}
}
