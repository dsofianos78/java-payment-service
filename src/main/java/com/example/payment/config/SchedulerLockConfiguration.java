package com.example.payment.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Lets a scheduled job say "one instance at a time" with @SchedulerLock
 * (docs/episodes/bonus-07). The lock is a row in the shedlock table (V8):
 * whoever updates it first runs, the others skip that run.
 *
 * The lock's times come from the database's clock, not each instance's, so two
 * instances whose clocks disagree still agree on when a lock expires.
 */
@Configuration(proxyBeanMethods = false)
// A run that never finishes (a hung call, a killed instance) holds the lock at most this long. Each job sets its own.
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
class SchedulerLockConfiguration {

	@Bean
	LockProvider lockProvider(JdbcTemplate jdbcTemplate) {
		return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
				.withJdbcTemplate(jdbcTemplate)
				.usingDbTime()
				.build());
	}
}
