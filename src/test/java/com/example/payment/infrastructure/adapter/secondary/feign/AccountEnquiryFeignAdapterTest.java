package com.example.payment.infrastructure.adapter.secondary.feign;

import com.example.payment.config.FeignConfiguration;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.AccountStatus;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import feign.FeignException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The real Feign client against a fake account system over real HTTP. Only
 * Feign and JSON are started: no web server, no database.
 */
@SpringBootTest(classes = {FeignConfiguration.class, AccountEnquiryFeignAdapter.class})
@ImportAutoConfiguration({FeignAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
		JacksonAutoConfiguration.class})
class AccountEnquiryFeignAdapterTest {

	@RegisterExtension
	static WireMockExtension accountSystem = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort())
			.build();

	@DynamicPropertySource
	static void accountSystemUrl(DynamicPropertyRegistry registry) {
		registry.add("account-system.url", accountSystem::baseUrl);
	}

	@Autowired
	AccountEnquiryFeignAdapter adapter;

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

		assertThatThrownBy(() -> adapter.findStatus(new AccountId("ACC-1"))).isInstanceOf(FeignException.class);
	}
}
