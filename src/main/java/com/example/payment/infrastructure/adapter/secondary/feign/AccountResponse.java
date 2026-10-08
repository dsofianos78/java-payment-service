package com.example.payment.infrastructure.adapter.secondary.feign;

/**
 * The account system's JSON, shaped by that system, not by us. It sends more
 * fields than this; only the ones the adapter reads are declared, and Jackson
 * ignores the rest, so new fields on their side don't break us.
 */
record AccountResponse(String accountNumber, String state) {
}
