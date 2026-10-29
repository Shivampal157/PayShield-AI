package com.payshield.domain;

public enum PaymentStatus {
    PENDING,
    APPROVED,
    FLAGGED_FRAUD,
    DECLINED,
    FAILED,
    REVIEW_REQUIRED
}
