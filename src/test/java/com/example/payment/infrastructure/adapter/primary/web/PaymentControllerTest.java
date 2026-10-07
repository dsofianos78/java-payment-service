package com.example.payment.infrastructure.adapter.primary.web;

import com.example.payment.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentControllerTest {

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
	void rejectsMissingFieldsWith400() throws Exception {
		mockMvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());
	}
}
