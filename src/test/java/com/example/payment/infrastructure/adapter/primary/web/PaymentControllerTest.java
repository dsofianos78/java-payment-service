package com.example.payment.infrastructure.adapter.primary.web;

import com.example.payment.TestcontainersConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentControllerTest {

	// The same fictional accounts compose.yaml serves locally.
	@RegisterExtension
	static WireMockExtension accountSystem = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort().usingFilesUnderDirectory("wiremock"))
			.build();

	@DynamicPropertySource
	static void accountSystemUrl(DynamicPropertyRegistry registry) {
		registry.add("account-system.url", accountSystem::baseUrl);
	}

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbc;

	@Test
	void createsAndStoresPayment() throws Exception {
		String response = mockMvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content("""
						{
						  "sourceAccountId": "ACC-10001",
						  "destinationAccountId": "ACC-20001",
						  "amount": 250.00,
						  "currency": "EUR",
						  "reference": "Invoice 12345"
						}
						"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.paymentId").isNotEmpty())
				.andExpect(jsonPath("$.status").value("CREATED"))
				.andExpect(jsonPath("$.amount").value(250.00))
				.andExpect(jsonPath("$.currency").value("EUR"))
				.andReturn().getResponse().getContentAsString();

		String paymentId = JsonPath.read(response, "$.paymentId");
		assertThat(jdbc.sql("SELECT status FROM payment WHERE id = ?::uuid").param(paymentId).query(String.class).single())
				.isEqualTo("CREATED");
	}

	@Test
	void createdPaymentCanBeReadBack() throws Exception {
		String response = mockMvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content("""
						{
						  "sourceAccountId": "ACC-10001",
						  "destinationAccountId": "ACC-30001",
						  "amount": 99.95,
						  "currency": "GBP",
						  "reference": "Rent October"
						}
						"""))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String paymentId = JsonPath.read(response, "$.paymentId");

		mockMvc.perform(get("/payments/{id}", paymentId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paymentId").value(paymentId))
				.andExpect(jsonPath("$.sourceAccountId").value("ACC-10001"))
				.andExpect(jsonPath("$.destinationAccountId").value("ACC-30001"))
				.andExpect(jsonPath("$.amount").value(99.95))
				.andExpect(jsonPath("$.currency").value("GBP"))
				.andExpect(jsonPath("$.reference").value("Rent October"))
				.andExpect(jsonPath("$.status").value("CREATED"));
	}

	@Test
	void unknownPaymentIs404() throws Exception {
		UUID unknown = UUID.randomUUID();

		mockMvc.perform(get("/payments/{id}", unknown))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value("Payment " + unknown + " does not exist"));
	}

	@Test
	void malformedPaymentIdIs400() throws Exception {
		mockMvc.perform(get("/payments/not-a-uuid"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("Invalid payment id: not-a-uuid"));
	}

	@Test
	void rejectsBusinessRuleViolationWith400() throws Exception {
		mockMvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content("""
						{
						  "sourceAccountId": "ACC-10001",
						  "destinationAccountId": "ACC-20001",
						  "amount": 250.00,
						  "currency": "JPY",
						  "reference": "Invoice 12345"
						}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("Unsupported currency: JPY"));
	}

	@Test
	void rejectsInactiveAccountWith400() throws Exception {
		mockMvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content("""
						{
						  "sourceAccountId": "ACC-10001",
						  "destinationAccountId": "ACC-90001",
						  "amount": 250.00,
						  "currency": "EUR",
						  "reference": "Invoice 12345"
						}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("Destination account ACC-90001 is not active"));
	}

	@Test
	void rejectsAccountTheAccountSystemDoesNotKnowWith400() throws Exception {
		mockMvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content("""
						{
						  "sourceAccountId": "ACC-99999",
						  "destinationAccountId": "ACC-20001",
						  "amount": 250.00,
						  "currency": "EUR",
						  "reference": "Invoice 12345"
						}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("Source account ACC-99999 does not exist"));
	}

	@Test
	void rejectsAmountOverTheLimitWith400() throws Exception {
		mockMvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content("""
						{
						  "sourceAccountId": "ACC-10001",
						  "destinationAccountId": "ACC-20001",
						  "amount": 10000.01,
						  "currency": "EUR",
						  "reference": "Invoice 12345"
						}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("Payment amount must not exceed 10000.00 EUR"));
	}

	@Test
	void executesAPaymentToCompletedAndStoresIt() throws Exception {
		String paymentId = createPayment("Invoice 12345");

		mockMvc.perform(post("/payments/{id}/execute", paymentId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));

		mockMvc.perform(get("/payments/{id}", paymentId))
				.andExpect(jsonPath("$.status").value("COMPLETED"));
	}

	@Test
	void paymentTheStubRejectsEndsFailed() throws Exception {
		String paymentId = createPayment("REJECT insufficient funds");

		mockMvc.perform(post("/payments/{id}/execute", paymentId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"));

		assertThat(jdbc.sql("SELECT status FROM payment WHERE id = ?::uuid").param(paymentId).query(String.class).single())
				.isEqualTo("FAILED");
	}

	@Test
	void executingTwiceIs409() throws Exception {
		String paymentId = createPayment("Invoice 12345");
		mockMvc.perform(post("/payments/{id}/execute", paymentId)).andExpect(status().isOk());

		mockMvc.perform(post("/payments/{id}/execute", paymentId))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("Payment " + paymentId + " is COMPLETED and cannot become AUTHORIZED"));
	}

	@Test
	void executingAnUnknownPaymentIs404() throws Exception {
		mockMvc.perform(post("/payments/{id}/execute", UUID.randomUUID()))
				.andExpect(status().isNotFound());
	}

	@Test
	void rejectsMissingFieldsWith400() throws Exception {
		mockMvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.title").value("Bad Request"));
	}

	private String createPayment(String reference) throws Exception {
		String response = mockMvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content("""
						{
						  "sourceAccountId": "ACC-10001",
						  "destinationAccountId": "ACC-20001",
						  "amount": 250.00,
						  "currency": "EUR",
						  "reference": "%s"
						}
						""".formatted(reference)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(response, "$.paymentId");
	}
}
