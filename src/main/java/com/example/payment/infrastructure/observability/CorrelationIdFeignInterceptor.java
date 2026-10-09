package com.example.payment.infrastructure.observability;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/** Passes the current request's correlation ID on to every external system we call. */
@Component
class CorrelationIdFeignInterceptor implements RequestInterceptor {

	@Override
	public void apply(RequestTemplate template) {
		String id = MDC.get(CorrelationIdFilter.MDC_KEY);
		if (id != null) {
			template.header(CorrelationIdFilter.HEADER, id);
		}
	}
}
