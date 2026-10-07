package com.example.payment.infrastructure.adapter.primary.web;

import com.example.payment.application.usecase.command.CreatePaymentCommand;
import com.example.payment.application.usecase.query.GetPaymentResult;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The controller only knows the primary ports, so it can be tested with
 * lambdas in place of the real services: no Spring context.
 */
class PaymentControllerPortTest {

	@Test
	void translatesRequestIntoCommandAndPaymentIntoResponse() throws Exception {
		AtomicReference<CreatePaymentCommand> received = new AtomicReference<>();
		MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new PaymentController(command -> {
			received.set(command);
			return Payment.create(new AccountId("ACC-1"), new AccountId("ACC-2"),
					new Money(new BigDecimal("9.99"), Currency.GBP), new PaymentReference("From stub"));
		}, query -> Optional.empty())).build();

		mockMvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content("""
						{
						  "sourceAccountId": "ACC-10001",
						  "destinationAccountId": "ACC-20001",
						  "amount": 250.00,
						  "currency": "EUR",
						  "reference": "Invoice 12345"
						}
						"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.sourceAccountId").value("ACC-1"))
				.andExpect(jsonPath("$.currency").value("GBP"))
				.andExpect(jsonPath("$.reference").value("From stub"));

		assertThat(received.get()).isEqualTo(new CreatePaymentCommand(
				"ACC-10001", "ACC-20001", new BigDecimal("250.00"), "EUR", "Invoice 12345"));
	}

	@Test
	void passesThePathIdAsAQueryAndMapsTheResult() throws Exception {
		GetPaymentResult stored = new GetPaymentResult("pay-1", "ACC-1", "ACC-2",
				new BigDecimal("9.99"), "GBP", "From stub", "CREATED");
		MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new PaymentController(
				command -> { throw new AssertionError("not a create"); },
				query -> Optional.of(stored).filter(r -> r.paymentId().equals(query.paymentId())))).build();

		mockMvc.perform(get("/payments/pay-1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paymentId").value("pay-1"))
				.andExpect(jsonPath("$.reference").value("From stub"));
		mockMvc.perform(get("/payments/pay-2"))
				.andExpect(status().isNotFound());
	}
}
