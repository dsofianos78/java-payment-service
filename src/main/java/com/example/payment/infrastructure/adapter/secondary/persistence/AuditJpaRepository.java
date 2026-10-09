package com.example.payment.infrastructure.adapter.secondary.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data's view of the table. Only the audit adapter uses it. */
interface AuditJpaRepository extends JpaRepository<AuditEntity, Long> {
}
