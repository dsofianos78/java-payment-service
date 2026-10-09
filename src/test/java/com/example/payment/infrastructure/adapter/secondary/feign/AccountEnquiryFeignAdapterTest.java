package com.example.payment.infrastructure.adapter.secondary.feign;

import com.example.payment.application.exception.AccountUnavailableException;
import com.example.payment.config.FeignConfiguration;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.AccountStatus;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.example.payment.infrastructure.observability.PaymentMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot.retry.autoconfigure.RetryAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The real Feign client against a fake account system over real HTTP. Only
 * Feign, JSON and Resilience4j are started: no web server, no database.
 */
@SpringBootTest(classes = {FeignConfiguration.class, AccountEnquiryFeignAdapter.class, PaymentMetrics.class,
		SimpleMeterRegistry.class})
@ImportAutoConfiguration({FeignAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
		JacksonAutoConfiguration.class, CircuitBreakerAutoConfiguration.class, RetryAutoConfiguration.class})
class AccountEnquiryFeignAdapterTest {

	@RegisterExtension
	static WireMockExtension accountSystem = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort())
			.build();

	@DynamicPropertySource
	static void accountSystemUrl(DynamicPropertyRegistry registry) {
		registry.add("account-system.url", accountSystem::baseUrl);
		// FeignConfiguration builds every client in the package, so each needs a URL.
		registry.add("authorization-system.url", accountSystem::baseUrl);
		registry.add("limit-system.url", accountSystem::baseUrl);
		registry.add("payment-system.url", accountSystem::baseUrl);
	}

	@Autowired
	AccountEnquiryFeignAdapter adapter;

	@Autowired
	MeterRegistry meterRegistry;

	@Autowired
	CircuitBreakerRegistry circuitBreakers;

	// One breaker per system for the whole context: failures in one test must not open it for the next.
	@BeforeEach
	void closeCircuitBreakers() {
		circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
	}

	@ParameterizedTest
	@CsvSource({"OPEN, ACTIVE", "FROZEN, BLOCKED", "CLOSED, CLOSED"})
	void mapsTheirStateToOurStatus(String theirState, AccountStatus ourStatus) {
		accountSystem.stubFor(get("/accounts/ACC-1").willReturn(okJson("""
				{ "accountNumber": "ACC-1", "accountName": "Any", "currency": "EUR", "state": "%s" }
				""".formatted(theirState))));

		assertThat(adapter.findStatus(new AccountId("ACC-1"))).contains(ourStatus);
	}

	@Test
	void unknownAccountIsEmpty() {
		accountSystem.stubFor(get("/accounts/ACC-404").willReturn(aResponse().withStatus(404)));

		assertThat(adapter.findStatus(new AccountId("ACC-404"))).isEmpty();
	}

	@Test
	void unknownStateFailsClosed() {
		accountSystem.stubFor(get("/accounts/ACC-1").willReturn(okJson("""
				{ "accountNumber": "ACC-1", "state": "DORMANT" }
				""")));

		assertThatIllegalStateException().isThrownBy(() -> adapter.findStatus(new AccountId("ACC-1")))
				.withMessage("Unknown account state from account system: DORMANT");
	}

	@Test
	void serverErrorIsNotMistakenForUnknownAccount() {
		accountSystem.stubFor(get("/accounts/ACC-1").willReturn(aResponse().withStatus(503)));
		double errorsBefore = meterRegistry.counter("external.account.errors").count();

		assertThatThrownBy(() -> adapter.findStatus(new AccountId("ACC-1"))).isInstanceOf(AccountUnavailableException.class)
				.hasMessage("Account system is unavailable");
		assertThat(meterRegistry.counter("external.account.errors").count()).isEqualTo(errorsBefore + 1);
		accountSystem.verify(3, getRequestedFor(urlEqualTo("/accounts/ACC-1")));
	}

	@Test
	void aFailedAttemptIsRetried() {
		accountSystem.stubFor(get("/accounts/ACC-1").inScenario("flaky").whenScenarioStateIs(STARTED)
				.willReturn(aResponse().withStatus(503)).willSetStateTo("recovered"));
		accountSystem.stubFor(get("/accounts/ACC-1").inScenario("flaky").whenScenarioStateIs("recovered")
				.willReturn(okJson("{ \"accountNumber\": \"ACC-1\", \"state\": \"OPEN\" }")));

		assertThat(adapter.findStatus(new AccountId("ACC-1"))).contains(AccountStatus.ACTIVE);
		accountSystem.verify(2, getRequestedFor(urlEqualTo("/accounts/ACC-1")));
	}

	@Test
	void unknownAccountIsAnAnswerAndIsNotRetried() {
		accountSystem.stubFor(get("/accounts/ACC-404").willReturn(aResponse().withStatus(404)));

		adapter.findStatus(new AccountId("ACC-404"));
		adapter.findStatus(new AccountId("ACC-404"));
		adapter.findStatus(new AccountId("ACC-404"));

		accountSystem.verify(3, getRequestedFor(urlEqualTo("/accounts/ACC-404")));
		assertThat(circuitBreakers.circuitBreaker("account-system").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	void afterEnoughFailuresTheCircuitOpensAndTheSystemIsNoLongerCalled() {
		accountSystem.stubFor(get("/accounts/ACC-1").willReturn(aResponse().withStatus(503)));
		// Two lookups, three attempts each: six failed calls, past the breaker's minimum of five.
		assertThatThrownBy(() -> adapter.findStatus(new AccountId("ACC-1"))).isInstanceOf(AccountUnavailableException.class);
		assertThatThrownBy(() -> adapter.findStatus(new AccountId("ACC-1"))).isInstanceOf(AccountUnavailableException.class);
		accountSystem.resetRequests();

		assertThatThrownBy(() -> adapter.findStatus(new AccountId("ACC-1"))).isInstanceOf(AccountUnavailableException.class);
		accountSystem.verify(0, getRequestedFor(urlEqualTo("/accounts/ACC-1")));
	}
}
