package com.payshield.service;

import com.payshield.domain.OutboxStatus;
import com.payshield.domain.PaymentStatus;
import com.payshield.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Records domain-level Micrometer metrics for Prometheus scraping (/actuator/prometheus)
 * and real-time operational percentile telemetry (/api/v1/risk/metrics).
 */
@Service
public class PaymentMetricsRecorder {

    private final MeterRegistry meterRegistry;
    private final Timer paymentExecutionTimer;
    private final Counter idempotentReplayCounter;
    private final Counter velocityBreachCounter;
    private final Counter analystApprovalCounter;
    private final Counter analystDeclineCounter;

    public PaymentMetricsRecorder(MeterRegistry meterRegistry, OutboxEventRepository outboxEventRepository) {
        this.meterRegistry = meterRegistry;
        this.paymentExecutionTimer = Timer.builder("payshield.payments.execution")
                .description("End-to-end transactional payment execution latency including lock acquisition and 8-D vector scoring")
                .publishPercentiles(0.50, 0.95, 0.99)
                .register(meterRegistry);

        this.idempotentReplayCounter = Counter.builder("payshield.payments.idempotent_replays.total")
                .description("Duplicate payment requests safely deduplicated via Idempotency-Key")
                .register(meterRegistry);

        this.velocityBreachCounter = Counter.builder("payshield.risk.velocity_breaches.total")
                .description("Transactions exceeding rolling sliding-window velocity threshold")
                .register(meterRegistry);

        this.analystApprovalCounter = Counter.builder("payshield.risk.analyst_reviews.total")
                .tag("decision", "APPROVE")
                .description("Quarantined transactions approved and settled by Risk Analysts")
                .register(meterRegistry);

        this.analystDeclineCounter = Counter.builder("payshield.risk.analyst_reviews.total")
                .tag("decision", "DECLINE")
                .description("Quarantined transactions declined by Risk Analysts")
                .register(meterRegistry);

        for (OutboxStatus status : OutboxStatus.values()) {
            Gauge.builder("payshield.outbox.backlog.events", outboxEventRepository,
                            repo -> repo.countByStatus(status))
                    .tag("status", status.name())
                    .description("Current count of transactional outbox events by status")
                    .register(meterRegistry);
        }
    }

    public void recordPaymentProcessed(PaymentStatus status, String currency, long durationNanos, boolean velocityExceeded) {
        paymentExecutionTimer.record(Math.max(1L, durationNanos), TimeUnit.NANOSECONDS);
        Counter.builder("payshield.payments.decisions.total")
                .tag("status", status.name())
                .tag("currency", currency.toUpperCase())
                .description("Processed payment decisions partitioned by status and currency")
                .register(meterRegistry)
                .increment();
        if (velocityExceeded) {
            velocityBreachCounter.increment();
        }
    }

    public void recordIdempotentReplay() {
        idempotentReplayCounter.increment();
    }

    public void recordAnalystReview(String decision) {
        if ("APPROVE".equalsIgnoreCase(decision)) {
            analystApprovalCounter.increment();
        } else {
            analystDeclineCounter.increment();
        }
    }

    public Map<String, Object> snapshotLatencyAndCounters() {
        HistogramSnapshot snapshot = paymentExecutionTimer.takeSnapshot();
        double p50Ms = 0.0;
        double p95Ms = 0.0;
        double p99Ms = 0.0;
        for (ValueAtPercentile vp : snapshot.percentileValues()) {
            double p = vp.percentile();
            if (Math.abs(p - 0.50) < 0.001) {
                p50Ms = roundTwoDecimals(vp.value(TimeUnit.MILLISECONDS));
            } else if (Math.abs(p - 0.95) < 0.001) {
                p95Ms = roundTwoDecimals(vp.value(TimeUnit.MILLISECONDS));
            } else if (Math.abs(p - 0.99) < 0.001) {
                p99Ms = roundTwoDecimals(vp.value(TimeUnit.MILLISECONDS));
            }
        }

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("recordedExecutions", snapshot.count());
        map.put("p50LatencyMs", p50Ms);
        map.put("p95LatencyMs", p95Ms);
        map.put("p99LatencyMs", p99Ms);
        map.put("meanLatencyMs", roundTwoDecimals(snapshot.mean(TimeUnit.MILLISECONDS)));
        map.put("maxLatencyMs", roundTwoDecimals(snapshot.max(TimeUnit.MILLISECONDS)));
        map.put("idempotentReplaysTotal", (long) idempotentReplayCounter.count());
        map.put("velocityBreachesTotal", (long) velocityBreachCounter.count());
        map.put("analystApprovalsTotal", (long) analystApprovalCounter.count());
        map.put("analystDeclinesTotal", (long) analystDeclineCounter.count());
        return map;
    }

    private static double roundTwoDecimals(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.round(value * 100.0) / 100.0;
    }
}
