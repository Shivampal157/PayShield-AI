package com.payshield;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payshield.config.AppProperties;
import com.payshield.domain.PaymentStatus;
import com.payshield.service.FraudVectorEngine;
import com.payshield.service.FraudVectorEngine.FeatureContext;
import com.payshield.service.FraudVectorEngine.FraudEvaluationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class FraudVectorEngineTest {

    private FraudVectorEngine engine;
    private AppProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        properties.getOpensearch().setEnabled(false); // Deterministic unit test baseline
        engine = new FraudVectorEngine(properties, new ObjectMapper());
    }

    @Test
    @DisplayName("8-D feature vector extraction normalizes all 8 dimensions into [0.0, 1.0]")
    void featureVectorIsNormalized() {
        FeatureContext ctx = new FeatureContext(
                new BigDecimal("250.00"),
                "USD",
                "SAAS_SUBSCRIPTION",
                "US",
                "US",
                25.0,
                0.95,
                365,
                1,
                Instant.parse("2026-10-22T14:00:00Z")
        );

        double[] vector = engine.extractFeatureVector(ctx);
        assertThat(vector).hasSize(FraudVectorEngine.VECTOR_DIMENSION);
        for (double v : vector) {
            assertThat(v).isBetween(0.0, 1.0);
        }
        // Domestic corridor with 25km IP distance has very low f1 anomaly
        assertThat(vector[1]).isLessThan(0.05);
        // Velocity count 1 => f3 = 0.0
        assertThat(vector[3]).isEqualTo(0.0);
        // 365 day old account => f7 = 0.0
        assertThat(vector[7]).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Score normalization bounds heuristic risk strictly within [0.00, 0.99]")
    void scoreNormalizationIsStrictlyBounded() {
        double[] identicalHigh = new double[]{1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0};
        double rawCosine = FraudVectorEngine.computeCosineSimilarity(identicalHigh, identicalHigh);
        assertThat(rawCosine).isCloseTo(1.0, within(1e-9));

        double normalized = FraudVectorEngine.normalizeSimilarityToRiskScore(rawCosine, identicalHigh, identicalHigh);
        assertThat(normalized).isEqualTo(0.99);

        double[] zeroVec = new double[8];
        assertThat(FraudVectorEngine.normalizeSimilarityToRiskScore(0.0, zeroVec, identicalHigh)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Low-risk domestic transaction scores below flag threshold and is APPROVED")
    void normalDomesticTransactionIsApproved() {
        FeatureContext normalCtx = new FeatureContext(
                new BigDecimal("45.00"),
                "USD",
                "GROCERY",
                "US",
                "US",
                10.0,
                0.98,
                500,
                1,
                Instant.parse("2026-10-22T15:00:00Z")
        );

        FraudEvaluationResult result = engine.evaluateTransaction(normalCtx, false);
        assertThat(result.recommendedStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(result.riskScore()).isBetween(0.0, properties.getRisk().getFlagThreshold());
        assertThat(result.topSimilarCases()).hasSize(3);
        assertThat(result.methodologyDisclaimer()).contains("not a calibrated probability");
    }

    @Test
    @DisplayName("High-risk cross-border crypto drain transaction scores high and is FLAGGED_FRAUD")
    void highRiskSyntheticScenarioIsFlaggedFraud() {
        FeatureContext anomalyCtx = new FeatureContext(
                new BigDecimal("6500.00"),
                "USD",
                "CRYPTO_EXCHANGE",
                "US",
                "SG",
                8900.0,
                0.10,
                3,
                4,
                Instant.parse("2026-10-22T02:15:00Z")
        );

        FraudEvaluationResult result = engine.evaluateTransaction(anomalyCtx, false);
        assertThat(result.recommendedStatus()).isEqualTo(PaymentStatus.FLAGGED_FRAUD);
        assertThat(result.riskScore()).isBetween(properties.getRisk().getDeclineThreshold(), 0.99);
        assertThat(result.topSimilarCases()).isNotEmpty();
        assertThat(result.topSimilarCases().get(0).caseId()).startsWith("SYNTH-FRAUD-");
    }

    @Test
    @DisplayName("OpenSearch unavailable and empty-index scenarios fall back deterministically")
    void openSearchUnavailableAndEmptyIndexFallbacks() {
        properties.getOpensearch().setEnabled(true);
        engine.setForceUnavailableForTest(true);

        FeatureContext ctx = new FeatureContext(
                new BigDecimal("3200.00"),
                "USD",
                "OFFSHORE_WIRE",
                "US",
                "CH",
                6400.0,
                0.20,
                5,
                2,
                Instant.parse("2026-10-22T03:00:00Z")
        );

        FraudEvaluationResult unavailableResult = engine.evaluateTransaction(ctx, false);
        assertThat(unavailableResult.openSearchAvailable()).isFalse();
        assertThat(unavailableResult.scoringSource()).isEqualTo("DETERMINISTIC_HEURISTIC_FALLBACK");
        assertThat(unavailableResult.topSimilarCases()).hasSize(3);

        engine.setForceUnavailableForTest(false);
        engine.setSimulateEmptyIndexForTest(true);
        FraudEvaluationResult emptyIndexResult = engine.evaluateTransaction(ctx, false);
        assertThat(emptyIndexResult.scoringSource()).isEqualTo("DETERMINISTIC_HEURISTIC_EMPTY_INDEX_FALLBACK");
        assertThat(emptyIndexResult.riskScore()).isEqualTo(unavailableResult.riskScore());
    }
}
