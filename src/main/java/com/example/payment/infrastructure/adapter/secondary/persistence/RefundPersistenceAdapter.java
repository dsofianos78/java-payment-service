package com.example.payment.infrastructure.adapter.secondary.persistence;

import com.example.payment.application.port.secondary.RefundRepository;
import com.example.payment.domain.entity.Refund;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.RefundId;
import com.example.payment.domain.valueobject.RefundStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Implements the application's RefundRepository with JPA and PostgreSQL. */
@Component
public class RefundPersistenceAdapter implements RefundRepository {

	private final RefundJpaRepository jpaRepository;

	RefundPersistenceAdapter(RefundJpaRepository jpaRepository) {
		this.jpaRepository = jpaRepository;
	}

	@Override
	public boolean save(Refund refund, String idempotencyKey) {
		return jpaRepository.insertIfKeyFree(refund.id().value(), refund.paymentId().value(), refund.amount().amount(),
				refund.amount().currency().name(), refund.status().name(), idempotencyKey, Instant.now()) == 1;
	}

	@Override
	public boolean updateStatus(Refund refund, RefundStatus expected) {
		return jpaRepository.updateStatus(refund.id().value(), expected.name(), refund.status().name(), Instant.now()) == 1;
	}

	@Override
	public List<Refund> findByPaymentId(PaymentId paymentId) {
		return jpaRepository.findByPaymentIdOrderByCreatedAtAsc(paymentId.value()).stream()
				.map(RefundPersistenceAdapter::toDomain)
				.toList();
	}

	@Override
	public Optional<Refund> findByIdempotencyKey(String idempotencyKey) {
		return jpaRepository.findByIdempotencyKey(idempotencyKey).map(RefundPersistenceAdapter::toDomain);
	}

	@Override
	public List<Refund> findProcessingSince(Instant before) {
		return jpaRepository.findByStatusAndStatusChangedAtLessThanEqual(RefundStatus.PROCESSING.name(), before).stream()
				.map(RefundPersistenceAdapter::toDomain)
				.toList();
	}

	// Through the domain's constructors, as PaymentEntityMapper: a corrupt row fails here.
	private static Refund toDomain(RefundEntity row) {
		return Refund.restore(new RefundId(row.getId()), new PaymentId(row.getPaymentId()),
				new Money(row.getAmount(), Currency.of(row.getCurrency())), RefundStatus.valueOf(row.getStatus()));
	}
}
