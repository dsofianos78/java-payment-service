package com.example.payment.infrastructure.adapter.secondary.feign;

import com.example.payment.application.exception.ExternalSystemUnavailableException;
import com.example.payment.config.FeignConfiguration;
import com.example.payment.infrastructure.observability.PaymentMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentReference;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
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
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The real Feign clients against fake authorization and limit systems over
 * real HTTP. Only Feign and JSON are started.
 */
@SpringBootTest(classes = {FeignConfiguration.class, AuthorizationAdapter.class, LimitAdapter.class,
		PaymentMetrics.class, SimpleMeterRegistry.class})
@ImportAutoConfiguration({FeignAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
		JacksonAutoConfiguration.class})
class AuthorizationAndLimitAdapterTest {

	@RegisterExtension
	static WireMockExtension externalSystems = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort())
			.build();

	@DynamicPropertySource
	static void urls(DynamicPropertyRegistry registry) {
		registry.add("account-system.url", externalSystems::baseUrl);
		registry.add("authorization-system.url", externalSystems::baseUrl);
		registry.add("limit-system.url", externalSystems::baseUrl);
	}

	@Autowired
	AuthorizationAdapter authorizationAdapter;

	@Autowired
	LimitAdapter limitAdapter;

	@Autowired
	MeterRegistry meterRegistry;

	private final Payment payment = Payment.create(new AccountId("ACC-1"), new AccountId("ACC-2"),
			new Money(new BigDecimal("250.00"), Currency.EUR), new PaymentReference("Invoice 12345"));

	@Test
	void sendsThePaymentInTheirTermsAndReadsApproval() {
		externalSystems.stubFor(post("/authorizations").withRequestBody(equalToJson("""
				{ "paymentId": "%s", "debtorAccount": "ACC-1", "creditorAccount": "ACC-2",
				  "amount": 250.00, "currency": "EUR" }
				""".formatted(payment.id()))).willReturn(okJson("{ \"decision\": \"APPROVED\" }")));

		assertThat(authorizationAdapter.isAuthorized(payment)).isTrue();
	}

	@Test
	void declinedIsNotAuthorized() {
		externalSystems.stubFor(post("/authorizations").willReturn(okJson("{ \"decision\": \"DECLINED\" }")));

		assertThat(authorizationAdapter.isAuthorized(payment)).isFalse();
	}

	@Test
	void unknownDecisionFailsClosed() {
		externalSystems.stubFor(post("/authorizations").willReturn(okJson("{ \"decision\": \"MAYBE\" }")));

		assertThatIllegalStateException().isThrownBy(() -> authorizationAdapter.isAuthorized(payment))
				.withMessage("Unknown decision from authorization system: MAYBE");
	}

	@Test
	void authorizationSystemDownIsUnavailable() {
		externalSystems.stubFor(post("/authorizations").willReturn(aResponse().withStatus(503)));
		double errorsBefore = paymentSystemErrors("authorization");

		assertThatThrownBy(() -> authorizationAdapter.isAuthorized(payment))
				.isInstanceOf(ExternalSystemUnavailableException.class)
				.hasMessage("Authorization system is unavailable");
		assertThat(paymentSystemErrors("authorization")).isEqualTo(errorsBefore + 1);
	}

	@Test
	void asksAboutTheSourceAccountAndReadsWithinLimit() {
		externalSystems.stubFor(post("/limit-checks").withRequestBody(equalToJson("""
				{ "accountNumber": "ACC-1", "amount": 250.00, "currency": "EUR" }
				""")).willReturn(okJson("{ \"result\": \"WITHIN_LIMIT\" }")));

		assertThat(limitAdapter.isWithinLimit(payment)).isTrue();
	}

	@Test
	void exceededIsNotWithinLimit() {
		externalSystems.stubFor(post("/limit-checks").willReturn(okJson("{ \"result\": \"LIMIT_EXCEEDED\" }")));

		assertThat(limitAdapter.isWithinLimit(payment)).isFalse();
	}

	@Test
	void limitSystemDownIsUnavailable() {
		externalSystems.stubFor(post("/limit-checks").willReturn(aResponse().withStatus(500)));
		double errorsBefore = paymentSystemErrors("limit");

		assertThatThrownBy(() -> limitAdapter.isWithinLimit(payment))
				.isInstanceOf(ExternalSystemUnavailableException.class)
				.hasMessage("Limit system is unavailable");
		assertThat(paymentSystemErrors("limit")).isEqualTo(errorsBefore + 1);
	}

	private double paymentSystemErrors(String system) {
		return meterRegistry.counter("external.payment.errors", "system", system).count();
	}
}
