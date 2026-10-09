package com.example.payment.infrastructure.adapter.primary.web;

import com.example.payment.application.exception.AccountNotFoundException;
import com.example.payment.application.exception.AccountUnavailableException;
import com.example.payment.application.exception.ExternalSystemUnavailableException;
import com.example.payment.application.exception.IdempotencyKeyInProgressException;
import com.example.payment.application.exception.IdempotencyKeyMismatchException;
import com.example.payment.application.exception.InvalidPaymentStateException;
import com.example.payment.application.exception.PaymentAuthorizationException;
import com.example.payment.application.exception.PaymentLimitExceededException;
import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.port.primary.CancelPaymentUseCase;
import com.example.payment.application.port.primary.RequestPaymentExecutionUseCase;
import com.example.payment.application.usecase.command.CreatePaymentCommand;
import com.example.payment.application.usecase.query.GetPaymentResult;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.UUID;
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

	private static final String VALID_REQUEST = """
			{
			  "sourceAccountId": "ACC-10001",
			  "destinationAccountId": "ACC-20001",
			  "amount": 250.00,
			  "currency": "EUR",
			  "reference": "Invoice 12345"
			}
			""";

	private static final RequestPaymentExecutionUseCase NO_EXECUTE = command -> { throw new AssertionError("not an execute"); };
	private static final CancelPaymentUseCase NO_CANCEL = command -> { throw new AssertionError("not a cancel"); };

	@Test
	void translatesRequestIntoCommandAndPaymentIntoResponse() throws Exception {
		AtomicReference<CreatePaymentCommand> received = new AtomicReference<>();
		MockMvc mockMvc = mockMvc(new PaymentController(command -> {
			received.set(command);
			return Payment.create(new AccountId("ACC-1"), new AccountId("ACC-2"),
					new Money(new BigDecimal("9.99"), Currency.GBP), new PaymentReference("From stub"));
		}, query -> { throw new AssertionError("not a query"); }, NO_EXECUTE, NO_CANCEL));

		mockMvc.perform(post("/payments").header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.sourceAccountId").value("ACC-1"))
				.andExpect(jsonPath("$.currency").value("GBP"))
				.andExpect(jsonPath("$.reference").value("From stub"));

		assertThat(received.get()).isEqualTo(new CreatePaymentCommand(
				"ACC-10001", "ACC-20001", new BigDecimal("250.00"), "EUR", "Invoice 12345", "key-1"));
	}

	@Test
	void passesThePathIdAsAQueryAndMapsTheResult() throws Exception {
		GetPaymentResult stored = new GetPaymentResult("pay-1", "ACC-1", "ACC-2",
				new BigDecimal("9.99"), "GBP", "From stub", "CREATED");
		MockMvc mockMvc = mockMvc(new PaymentController(
				command -> { throw new AssertionError("not a create"); },
				query -> {
					assertThat(query.paymentId()).isEqualTo("pay-1");
					return stored;
				}, NO_EXECUTE, NO_CANCEL));

		mockMvc.perform(get("/payments/pay-1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paymentId").value("pay-1"))
				.andExpect(jsonPath("$.reference").value("From stub"));
	}

	@Test
	void passesThePathIdAsAnExecuteCommandAndAnswers202WithThePaymentAsItIsNow() throws Exception {
		Payment queued = Payment.create(new AccountId("ACC-1"), new AccountId("ACC-2"),
				new Money(new BigDecimal("9.99"), Currency.GBP), new PaymentReference("From stub"));
		MockMvc mockMvc = mockMvc(new PaymentController(
				command -> { throw new AssertionError("not a create"); },
				query -> { throw new AssertionError("not a query"); },
				command -> {
					assertThat(command.paymentId()).isEqualTo("pay-1");
					return queued;
				}, NO_CANCEL));

		mockMvc.perform(post("/payments/pay-1/execute"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("CREATED"));
	}

	@Test
	void mapsAnInvalidPaymentStateTo409() throws Exception {
		mockMvc(new PaymentController(
				command -> { throw new AssertionError("not a create"); },
				query -> { throw new AssertionError("not a query"); },
				command -> { throw new InvalidPaymentStateException("Payment pay-1 is COMPLETED and cannot become PROCESSING",
						new IllegalStateException()); }, NO_CANCEL))
				.perform(post("/payments/pay-1/execute"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("Payment pay-1 is COMPLETED and cannot become PROCESSING"));
	}

	@Test
	void passesThePathIdAsACancelCommandAndMapsThePayment() throws Exception {
		Payment cancelled = Payment.create(new AccountId("ACC-1"), new AccountId("ACC-2"),
				new Money(new BigDecimal("9.99"), Currency.GBP), new PaymentReference("From stub"));
		cancelled.cancel();
		MockMvc mockMvc = mockMvc(new PaymentController(
				command -> { throw new AssertionError("not a create"); },
				query -> { throw new AssertionError("not a query"); }, NO_EXECUTE,
				command -> {
					assertThat(command.paymentId()).isEqualTo("pay-1");
					return cancelled;
				}));

		mockMvc.perform(post("/payments/pay-1/cancel"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELLED"));
	}

	// Every application exception has exactly one HTTP status, and its message becomes the detail.
	@Test
	void mapsApplicationExceptionsToHttpStatuses() throws Exception {
		assertCreateFailsWith(new PaymentValidationException("Unsupported currency: JPY"), 400);
		assertCreateFailsWith(new AccountNotFoundException("Source", new AccountId("ACC-99999")), 400);
		assertCreateFailsWith(new AccountUnavailableException(new RuntimeException("connection refused")), 503);
		assertCreateFailsWith(new IdempotencyKeyInProgressException(), 409);
		assertCreateFailsWith(new IdempotencyKeyMismatchException(), 422);
		assertCreateFailsWith(new PaymentAuthorizationException(new PaymentId(UUID.randomUUID())), 422);
		assertCreateFailsWith(new PaymentLimitExceededException(new PaymentId(UUID.randomUUID())), 422);
		assertCreateFailsWith(new ExternalSystemUnavailableException("Limit system", new RuntimeException("timeout")), 503);

		PaymentId unknown = new PaymentId(UUID.randomUUID());
		mockMvc(new PaymentController(
				command -> { throw new AssertionError("not a create"); },
				query -> { throw new PaymentNotFoundException(unknown); }, NO_EXECUTE, NO_CANCEL))
				.perform(get("/payments/{id}", unknown))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value("Payment " + unknown + " does not exist"));
	}

	@Test
	void doesNotLeakTheCauseOfAnUnavailableAccountSystem() throws Exception {
		mockMvc(new PaymentController(
				command -> { throw new AccountUnavailableException(new RuntimeException("10.0.0.7:8089 refused")); },
				query -> { throw new AssertionError("not a query"); }, NO_EXECUTE, NO_CANCEL))
				.perform(post("/payments").header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.detail").value("Account system is unavailable"));
	}

	private static void assertCreateFailsWith(RuntimeException thrown, int httpStatus) throws Exception {
		mockMvc(new PaymentController(
				command -> { throw thrown; },
				query -> { throw new AssertionError("not a query"); }, NO_EXECUTE, NO_CANCEL))
				.perform(post("/payments").header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
				.andExpect(status().is(httpStatus))
				.andExpect(jsonPath("$.status").value(httpStatus))
				.andExpect(jsonPath("$.detail").value(thrown.getMessage()));
	}

	private static MockMvc mockMvc(PaymentController controller) {
		return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new PaymentExceptionHandler()).build();
	}
}
