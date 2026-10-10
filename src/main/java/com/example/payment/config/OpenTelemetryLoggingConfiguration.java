package com.example.payment.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Configuration;

/**
 * Connects the OTEL appender of logback-spring.xml to the OpenTelemetry SDK
 * Boot builds, whose batch processor and OTLP exporter push log records to
 * Loki. Logback starts before any bean exists, so the appender holds the
 * first lines until this runs.
 *
 * Domain and application code log through SLF4J as before and know nothing
 * of this.
 */
@Configuration(proxyBeanMethods = false)
public class OpenTelemetryLoggingConfiguration implements InitializingBean {

	private final OpenTelemetry openTelemetry;

	OpenTelemetryLoggingConfiguration(OpenTelemetry openTelemetry) {
		this.openTelemetry = openTelemetry;
	}

	@Override
	public void afterPropertiesSet() {
		OpenTelemetryAppender.install(openTelemetry);
	}
}
