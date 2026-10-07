package com.example.payment.infrastructure.adapter.secondary.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Spring Data's view of the table. Only the persistence adapter uses it. */
interface PaymentJpaRepository extends JpaRepository<PaymentEntity, UUID> {
}
