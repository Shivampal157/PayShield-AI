package com.payshield.domain;

public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    DEAD_LETTER
}
