package com.example.payment.infrastructure.adapter.secondary.feign;

import com.example.payment.application.port.secondary.PaymentExecutionPort.Outcome;
import com.example.payment.config.FeignConfiguration;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentReference;
import com.example.payment.infrastructure.observability.PaymentMetrics;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot.retry.autoconfigure.RetryAutoConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real Feign client against a fake payment system over real HTTP. The
 * point is what does NOT happen: a call without a clear answer is never sent twice.
 */
@SpringBootTest(classes = {FeignConfiguration.class, PaymentExecutionAdapter.class, PaymentMetrics.class,
		SimpleMeterRegistry.class}, properties = "spring.cloud.openfeign.client.config.payment-system.read-timeout=300")
@ImportAutoConfiguration({FeignAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
		JacksonAutoConfiguration.class, CircuitBreakerAutoConfiguration.class, RetryAutoConfiguration.class})
class PaymentExecutionAdapterTest {

	@RegisterExtension
	static WireMockExtension paymentSystem = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort())
			.build();

	@DynamicPropertySource
	static void urls(DynamicPropertyRegistry registry) {
		registry.add("payment-system.url", paymentSystem::baseUrl);
		// FeignConfiguration builds every client in the package, so each needs a URL.
		registry.add("account-system.url", paymentSystem::baseUrl);
		registry.add("authorization-system.url", paymentSystem::baseUrl);
		registry.add("limit-system.url", paymentSystem::baseUrl);
	}

	@Autowired
	PaymentExecutionAdapter adapter;

	@Autowired
	MeterRegistry meterRegistry;

	@Autowired
	CircuitBreakerRegistry circuitBreakers;

	private final Payment payment = Payment.create(new AccountId("ACC-1"), new AccountId("ACC-2"),
			new Money(new BigDecimal("250.00"), Currency.EUR), new PaymentReference("Invoice 12345"));

	@BeforeEach
	void closeCircuitBreakers() {
		circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
	}

	@Test
	void sendsThePaymentWithItsIdAsIdempotencyKeyAndReadsSettled() {
		paymentSystem.stubFor(post("/payment-orders")
				.withHeader("Idempotency-Key", equalTo(payment.id().toString()))
				.withRequestBody(equalToJson("""
						{ "debtorAccount": "ACC-1", "creditorAccount": "ACC-2", "amount": 250.00,
						  "currency": "EUR", "reference": "Invoice 12345" }
						"""))
				.willReturn(okJson("{ \"status\": \"SETTLED\" }")));

		assertThat(adapter.execute(payment)).isEqualTo(Outcome.EXECUTED);
	}

	@Test
	void rejectedIsRejected() {
		paymentSystem.stubFor(post("/payment-orders").willReturn(okJson("{ \"status\": \"REJECTED\" }")));

		assertThat(adapter.execute(payment)).isEqualTo(Outcome.REJECTED);
	}

	@Test
	void aTimeoutIsUnknownAndIsSentOnlyOnce() {
		// They settle it, but the answer comes after we stopped listening: the money moved and we don't know.
		paymentSystem.stubFor(post("/payment-orders")
				.willReturn(okJson("{ \"status\": \"SETTLED\" }").withFixedDelay(1000)));
		double errorsBefore = executionErrors();

		assertThat(adapter.execute(payment)).isEqualTo(Outcome.UNKNOWN);
		paymentSystem.verify(1, postRequestedFor(urlEqualTo("/payment-orders")));
		assertThat(executionErrors()).isEqualTo(errorsBefore + 1);
	}

	@Test
	void aServerErrorIsUnknownAndIsSentOnlyOnce() {
		paymentSystem.stubFor(post("/payment-orders").willReturn(aResponse().withStatus(503)));

		assertThat(adapter.execute(payment)).isEqualTo(Outcome.UNKNOWN);
		paymentSystem.verify(1, postRequestedFor(urlEqualTo("/payment-orders")));
	}

	@Test
	void anAnswerWeDontUnderstandIsUnknownNotFailed() {
		paymentSystem.stubFor(post("/payment-orders").willReturn(okJson("{ \"status\": \"PENDING\" }")));

		assertThat(adapter.execute(payment)).isEqualTo(Outcome.UNKNOWN);
	}

	@Test
	void anOpenCircuitSendsNothing() {
		circuitBreakers.circuitBreaker("payment-system").transitionToOpenState();

		assertThat(adapter.execute(payment)).isEqualTo(Outcome.UNKNOWN);
		paymentSystem.verify(0, postRequestedFor(urlEqualTo("/payment-orders")));
	}

	private double executionErrors() {
		return meterRegistry.counter("external.payment.errors", "system", "execution").count();
	}
}
