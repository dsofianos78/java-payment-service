package com.example.payment.config;

import com.example.payment.infrastructure.adapter.secondary.feign.AccountEnquiryFeignAdapter;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

/**
 * Turns on Feign for the feign adapter package only. Kept off
 * {@code PaymentApplication} so test slices such as {@code @DataJpaTest}
 * don't try to build HTTP clients.
 */
@Configuration(proxyBeanMethods = false)
@EnableFeignClients(basePackageClasses = AccountEnquiryFeignAdapter.class)
public class FeignConfiguration {
}
