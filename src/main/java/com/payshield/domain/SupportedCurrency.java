package com.payshield.domain;

import java.util.Set;

public final class SupportedCurrency {
    public static final Set<String> SUPPORTED = Set.of("USD", "EUR", "GBP");

    private SupportedCurrency() {
    }

    public static boolean isValid(String currency) {
        return currency != null && SUPPORTED.contains(currency.trim().toUpperCase());
    }
}
