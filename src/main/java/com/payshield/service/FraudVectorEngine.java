package com.payshield.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payshield.config.AppProperties;
import com.payshield.domain.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 8-Dimensional Behavioral Vector & OpenSearch 2.12 k-NN Fraud Risk Engine.
 *
 * IMPORTANT DISCLOSURE:
 * Similarity scores produced by this engine are heuristic risk indicators in [0.00, 0.99],
 * NOT calibrated statistical probabilities. The five seeded historical patterns are synthetic
 * reference scenarios for portfolio demonstration, not a production-validated ML model.
 */
@Service
public class FraudVectorEngine {

    private static final Logger log = LoggerFactory.getLogger(FraudVectorEngine.class);

    public static final int VECTOR_DIMENSION = 8;
    public static final String INDEX_SCHEMA_VERSION = "v1.0";
    public static final String CALIBRATION_DISCLAIMER =
            "Heuristic cosine-similarity risk indicator (bounded 0.00–0.99) against 5 synthetic reference patterns; not a calibrated probability or certified ML model.";

    public static final List<String> FEATURE_NAMES = List.of(
            "f0_normalizedAmount",
            "f1_crossBorderIpAnomaly",
            "f2_merchantRiskCategory",
            "f3_velocityBurstScore",
            "f4_nocturnalTimeOfDayRisk",
            "f5_amountToBaselineRatio",
            "f6_untrustedDeviceAnomaly",
            "f7_newAccountImmaturity"
    );

    public record SyntheticFraudPattern(
            String caseId,
            String schemaVersion,
            String typology,
            String title,
            String summary,
            String policyReference,
            double[] vector,
            boolean synthetic
    ) {
    }

    public record SimilarCaseMatch(
            String caseId,
            String typology,
            String title,
            String summary,
            String policyReference,
            double rawCosineSimilarity,
            double normalizedRiskContribution,
            boolean synthetic,
            String retrievalSource
    ) {
    }

    public record ExplainableRiskFactor(
            String featureKey,
            String label,
            double featureValue,
            String severity,
            String explanation
    ) {
    }

    public record FeatureContext(
            BigDecimal amount,
            String currency,
            String merchantCategory,
            String sourceCountry,
            String destinationCountry,
            double ipDistanceKm,
            double deviceTrustScore,
            int accountAgeDays,
            int velocityCount,
            Instant timestampUtc
    ) {
    }

    public record FraudEvaluationResult(
            double riskScore,
            PaymentStatus recommendedStatus,
            double[] featureVector,
            List<ExplainableRiskFactor> riskFactors,
            List<SimilarCaseMatch> topSimilarCases,
            String scoringSource,
            boolean openSearchAvailable,
            String methodologyDisclaimer
    ) {
    }

    private static final List<SyntheticFraudPattern> SYNTHETIC_PATTERNS = List.of(
            new SyntheticFraudPattern(
                    "SYNTH-FRAUD-001",
                    INDEX_SCHEMA_VERSION,
                    "CROSS_BORDER_CRYPTO_DRAIN",
                    "[SYNTHETIC] Cross-Border Digital Asset Exchange Account Drain",
                    "High-value transfer ($4,800+) to offshore CRYPTO_EXCHANGE with cross-border corridor mismatch and 6,500 km IP geodesic anomaly.",
                    "POL-EFT-101, POL-MCC-409, POL-GEO-302",
                    new double[]{0.92, 0.88, 0.95, 0.35, 0.70, 0.90, 0.80, 0.65},
                    true
            ),
            new SyntheticFraudPattern(
                    "SYNTH-FRAUD-002",
                    INDEX_SCHEMA_VERSION,
                    "VELOCITY_SCRIPT_BURST",
                    "[SYNTHETIC] Automated High-Velocity Card/Wallet Testing Burst",
                    "Rapid script-driven payment attempts (>5 transfers within 60 seconds) probing account limits across retail and digital merchants.",
                    "POL-VEL-204",
                    new double[]{0.35, 0.25, 0.45, 0.98, 0.50, 0.30, 0.60, 0.55},
                    true
            ),
            new SyntheticFraudPattern(
                    "SYNTH-FRAUD-003",
                    INDEX_SCHEMA_VERSION,
                    "NOCTURNAL_ATO_UNTRUSTED_DEVICE",
                    "[SYNTHETIC] Nocturnal Account Takeover (ATO) from Unrecognized Device",
                    "Off-hours UTC window (01:00–04:30 UTC) transfer initiated from an untrusted device fingerprint (trustScore < 0.20) with elevated IP distance.",
                    "POL-ATO-501, POL-GEO-302",
                    new double[]{0.78, 0.75, 0.70, 0.40, 0.90, 0.75, 0.92, 0.30},
                    true
            ),
            new SyntheticFraudPattern(
                    "SYNTH-FRAUD-004",
                    INDEX_SCHEMA_VERSION,
                    "NEW_ACCOUNT_OFFSHORE_MULE",
                    "[SYNTHETIC] Newly Provisioned Account High-Value Offshore Wire",
                    "Account younger than 7 days executing high-value OFFSHORE_WIRE remittance exceeding 5x baseline average.",
                    "POL-EFT-101, POL-MCC-409",
                    new double[]{0.88, 0.82, 0.88, 0.30, 0.55, 0.92, 0.75, 0.98},
                    true
            ),
            new SyntheticFraudPattern(
                    "SYNTH-FRAUD-005",
                    INDEX_SCHEMA_VERSION,
                    "IMPOSSIBLE_TRAVEL_LUXURY_GOODS",
                    "[SYNTHETIC] Impossible-Travel Geodesic IP Mismatch on Luxury Merchant",
                    "Session originating 8,200 km from registered jurisdiction purchasing high-ticket LUXURY_GOODS on an unverified browser session.",
                    "POL-GEO-302, POL-MCC-409",
                    new double[]{0.82, 0.94, 0.72, 0.25, 0.60, 0.80, 0.85, 0.45},
                    true
            )
    );

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AtomicBoolean openSearchHealthy = new AtomicBoolean(false);
    private volatile boolean forceUnavailableForTest = false;
    private volatile boolean simulateEmptyIndexForTest = false;

    public FraudVectorEngine(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(1500))
                .build();
    }

    /**
     * Computes the explicitly documented 8-dimensional normalized behavioral feature vector in [0.0, 1.0]^8.
     */
    public double[] extractFeatureVector(FeatureContext ctx) {
        double amountVal = ctx.amount() != null ? Math.max(0.0, ctx.amount().doubleValue()) : 0.0;

        // f0: Normalized logarithmic monetary amount in [0.0, 1.0] (10,000+ USD saturates at 1.0)
        double f0 = clamp(Math.log10(1.0 + amountVal) / Math.log10(1.0 + 10000.0));

        // f1: Cross-border corridor + geodesic IP distance anomaly in [0.0, 1.0]
        boolean crossBorder = ctx.sourceCountry() != null && ctx.destinationCountry() != null
                && !ctx.sourceCountry().trim().equalsIgnoreCase(ctx.destinationCountry().trim());
        double crossBorderBase = crossBorder ? 0.45 : 0.0;
        double distanceComponent = clamp(Math.max(0.0, ctx.ipDistanceKm()) / 10000.0) * 0.55;
        double f1 = clamp(crossBorderBase + distanceComponent);

        // f2: Merchant risk category encoding in [0.0, 1.0]
        double f2 = encodeMerchantCategoryRisk(ctx.merchantCategory());

        // f3: Sliding-window transaction velocity burst indicator in [0.0, 1.0]
        double f3 = clamp(Math.max(0.0, (ctx.velocityCount() - 1.0) / 5.0));

        // f4: Time-of-day UTC nocturnal risk encoding in [0.0, 1.0]
        Instant ts = ctx.timestampUtc() != null ? ctx.timestampUtc() : Instant.now();
        int utcHour = ts.atZone(ZoneOffset.UTC).getHour();
        double f4;
        if (utcHour >= 0 && utcHour <= 4) {
            f4 = 0.85;
        } else if (utcHour == 5 || utcHour >= 22) {
            f4 = 0.48;
        } else {
            f4 = 0.12;
        }

        // f5: Amount-to-baseline ($250.00) ratio in [0.0, 1.0]
        double f5 = clamp((amountVal / 250.0) / 12.0);

        // f6: Untrusted device anomaly (1.0 - deviceTrustScore) in [0.0, 1.0]
        double f6 = clamp(1.0 - clamp(ctx.deviceTrustScore()));

        // f7: Account immaturity inverse risk (1.0 - min(1.0, ageDays / 365)) in [0.0, 1.0]
        double f7 = clamp(1.0 - clamp(Math.max(0, ctx.accountAgeDays()) / 365.0));

        return new double[]{
                round4(f0), round4(f1), round4(f2), round4(f3),
                round4(f4), round4(f5), round4(f6), round4(f7)
        };
    }

    /**
     * Computes raw cosine similarity cos(theta) = (a . b) / (||a|| * ||b||) in [-1.0, 1.0].
     */
    public static double computeCosineSimilarity(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            throw new IllegalArgumentException("Vectors must be non-null and of equal dimension");
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA <= 1e-12 || normB <= 1e-12) {
            return 0.0;
        }
        double cosine = dot / (Math.sqrt(normA) * Math.sqrt(normB));
        return Math.max(-1.0, Math.min(1.0, cosine));
    }

    /**
     * Defined, tested score-normalization function mapping raw cosine similarity and vector activation
     * magnitude into a bounded heuristic risk score in [0.00, 0.99].
     *
     * Rationale:
     * Pure directional cosine similarity between a low-magnitude normal transaction [0.1, 0, 0.1, ...]
     * and a high-magnitude fraud vector [0.9, 0.9, 0.9, ...] would over-score quiet transactions that
     * happen to point in a similar octant. Weighting positive cosine alignment by the L2 activation ratio
     * min(1.0, ||query||_2 / ||reference||_2) ensures normal low-activation transfers score near 0.05–0.22
     * while high-activation anomalous transfers matching a fraud pattern score up to 0.99.
     */
    public static double normalizeSimilarityToRiskScore(double rawCosineSimilarity,
                                                        double[] queryVector,
                                                        double[] referenceVector) {
        double positiveCosine = Math.max(0.0, Math.min(1.0, rawCosineSimilarity));
        double normQuery = l2Norm(queryVector);
        double normRef = l2Norm(referenceVector);
        if (normQuery <= 1e-9 || normRef <= 1e-9) {
            return 0.0;
        }
        double magnitudeRatio = Math.min(1.0, normQuery / normRef);
        double combined = positiveCosine * (0.35 + 0.65 * magnitudeRatio);
        double bounded = Math.max(0.0, Math.min(0.99, combined));
        return round4(bounded);
    }

    /**
     * Evaluates transaction fraud risk using OpenSearch 2.12 k-NN (with automatic deterministic fallback).
     */
    public FraudEvaluationResult evaluateTransaction(FeatureContext context, boolean velocityExceeded) {
        double[] queryVector = extractFeatureVector(context);
        List<ExplainableRiskFactor> riskFactors = buildExplainableFactors(context, queryVector, velocityExceeded);

        List<SimilarCaseMatch> topMatches = new ArrayList<>();
        String scoringSource = "OPENSEARCH_KNN_COSINESIMIL";
        boolean osUsed = false;

        if (properties.getOpensearch().isEnabled() && !forceUnavailableForTest && !simulateEmptyIndexForTest) {
            try {
                List<SimilarCaseMatch> osMatches = queryOpenSearchKnn(queryVector, 3);
                if (!osMatches.isEmpty()) {
                    topMatches = osMatches;
                    osUsed = true;
                    openSearchHealthy.set(true);
                }
            } catch (Exception ex) {
                openSearchHealthy.set(false);
                log.debug("OpenSearch k-NN query unavailable; activating deterministic fallback scoring: {}", ex.getMessage());
            }
        } else {
            openSearchHealthy.set(false);
        }

        if (topMatches.isEmpty()) {
            scoringSource = simulateEmptyIndexForTest
                    ? "DETERMINISTIC_HEURISTIC_EMPTY_INDEX_FALLBACK"
                    : "DETERMINISTIC_HEURISTIC_FALLBACK";
            topMatches = computeDeterministicFallbackMatches(queryVector, 3, scoringSource);
        }

        double maxPatternScore = topMatches.stream()
                .mapToDouble(SimilarCaseMatch::normalizedRiskContribution)
                .max()
                .orElse(0.0);

        double heuristicRuleFloor = computeRuleFloor(queryVector, velocityExceeded);
        double finalRiskScore = round4(Math.min(0.99, Math.max(maxPatternScore, heuristicRuleFloor)));

        PaymentStatus decision;
        if (velocityExceeded || finalRiskScore >= properties.getRisk().getDeclineThreshold()) {
            decision = PaymentStatus.FLAGGED_FRAUD;
        } else if (finalRiskScore >= properties.getRisk().getFlagThreshold()) {
            decision = PaymentStatus.REVIEW_REQUIRED;
        } else {
            decision = PaymentStatus.APPROVED;
        }

        return new FraudEvaluationResult(
                finalRiskScore,
                decision,
                queryVector,
                riskFactors,
                topMatches,
                scoringSource,
                osUsed,
                CALIBRATION_DISCLAIMER
        );
    }

    /**
     * Initializes the versioned OpenSearch index "fraud-behavior-vectors" and seeds the 5 synthetic patterns
     * if OpenSearch 2.12 is reachable. Safe no-op if OpenSearch is offline.
     */
    public boolean initializeAndSeedOpenSearchIndex() {
        if (!properties.getOpensearch().isEnabled() || forceUnavailableForTest) {
            openSearchHealthy.set(false);
            return false;
        }
        String baseUrl = buildOpenSearchBaseUrl();
        String indexName = properties.getOpensearch().getIndexName();
        try {
            // 1. Check if index exists
            HttpRequest headReq = requestBuilder(baseUrl + "/" + indexName)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> headRes = httpClient.send(headReq, HttpResponse.BodyHandlers.discarding());

            if (headRes.statusCode() == 404) {
                String mappingJson = """
                        {
                          "settings": {
                            "index": {
                              "knn": true,
                              "number_of_shards": 1,
                              "number_of_replicas": 0
                            }
                          },
                          "mappings": {
                            "properties": {
                              "caseId": { "type": "keyword" },
                              "schemaVersion": { "type": "keyword" },
                              "typology": { "type": "keyword" },
                              "title": { "type": "text" },
                              "summary": { "type": "text" },
                              "policyReference": { "type": "keyword" },
                              "synthetic": { "type": "boolean" },
                              "behaviorVector": {
                                "type": "knn_vector",
                                "dimension": 8,
                                "method": {
                                  "name": "hnsw",
                                  "space_type": "cosinesimil",
                                  "engine": "nmslib"
                                }
                              }
                            }
                          }
                        }
                        """;
                HttpRequest createReq = requestBuilder(baseUrl + "/" + indexName)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(mappingJson, StandardCharsets.UTF_8))
                        .build();
                httpClient.send(createReq, HttpResponse.BodyHandlers.ofString());
            }

            // 2. Seed all 5 clearly labeled synthetic historical fraud patterns idempotently by caseId
            for (SyntheticFraudPattern pattern : SYNTHETIC_PATTERNS) {
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("caseId", pattern.caseId());
                doc.put("schemaVersion", pattern.schemaVersion());
                doc.put("typology", pattern.typology());
                doc.put("title", pattern.title());
                doc.put("summary", pattern.summary());
                doc.put("policyReference", pattern.policyReference());
                doc.put("synthetic", true);
                doc.put("behaviorVector", pattern.vector());

                String body = objectMapper.writeValueAsString(doc);
                HttpRequest putDoc = requestBuilder(baseUrl + "/" + indexName + "/_doc/" + pattern.caseId() + "?refresh=true")
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                httpClient.send(putDoc, HttpResponse.BodyHandlers.ofString());
            }

            openSearchHealthy.set(true);
            log.info("OpenSearch index '{}' ({}) verified and seeded with {} synthetic fraud reference patterns",
                    indexName, INDEX_SCHEMA_VERSION, SYNTHETIC_PATTERNS.size());
            return true;
        } catch (Exception ex) {
            openSearchHealthy.set(false);
            log.info("OpenSearch not reachable at {}; FraudVectorEngine running in deterministic fallback mode ({})",
                    baseUrl, ex.getMessage());
            return false;
        }
    }

    private List<SimilarCaseMatch> queryOpenSearchKnn(double[] queryVector, int k) throws Exception {
        String baseUrl = buildOpenSearchBaseUrl();
        String indexName = properties.getOpensearch().getIndexName();

        Map<String, Object> knnQuery = Map.of(
                "size", k,
                "query", Map.of(
                        "knn", Map.of(
                                "behaviorVector", Map.of(
                                        "vector", queryVector,
                                        "k", k
                                )
                        )
                )
        );

        HttpRequest searchReq = requestBuilder(baseUrl + "/" + indexName + "/_search")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(knnQuery), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(searchReq, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("OpenSearch returned HTTP " + response.statusCode());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode hits = root.path("hits").path("hits");
        if (!hits.isArray() || hits.isEmpty()) {
            return List.of();
        }

        List<SimilarCaseMatch> matches = new ArrayList<>();
        for (JsonNode hit : hits) {
            JsonNode source = hit.path("_source");
            String caseId = source.path("caseId").asText();
            double[] refVector = findSyntheticVectorById(caseId);
            double rawCosine = computeCosineSimilarity(queryVector, refVector);
            double normalizedRisk = normalizeSimilarityToRiskScore(rawCosine, queryVector, refVector);

            matches.add(new SimilarCaseMatch(
                    caseId,
                    source.path("typology").asText("SYNTHETIC_PATTERN"),
                    source.path("title").asText(),
                    source.path("summary").asText(),
                    source.path("policyReference").asText("POL-EFT-101"),
                    round4(rawCosine),
                    normalizedRisk,
                    source.path("synthetic").asBoolean(true),
                    "OPENSEARCH_KNN"
            ));
        }
        matches.sort(Comparator.comparingDouble(SimilarCaseMatch::normalizedRiskContribution).reversed());
        return matches;
    }

    public List<SimilarCaseMatch> computeDeterministicFallbackMatches(double[] queryVector, int topK, String sourceLabel) {
        List<SimilarCaseMatch> matches = new ArrayList<>();
        for (SyntheticFraudPattern pattern : SYNTHETIC_PATTERNS) {
            double rawCosine = computeCosineSimilarity(queryVector, pattern.vector());
            double normalizedRisk = normalizeSimilarityToRiskScore(rawCosine, queryVector, pattern.vector());
            matches.add(new SimilarCaseMatch(
                    pattern.caseId(),
                    pattern.typology(),
                    pattern.title(),
                    pattern.summary(),
                    pattern.policyReference(),
                    round4(rawCosine),
                    normalizedRisk,
                    pattern.synthetic(),
                    sourceLabel
            ));
        }
        matches.sort(Comparator.comparingDouble(SimilarCaseMatch::normalizedRiskContribution).reversed());
        return matches.subList(0, Math.min(topK, matches.size()));
    }

    private List<ExplainableRiskFactor> buildExplainableFactors(FeatureContext ctx,
                                                                double[] v,
                                                                boolean velocityExceeded) {
        List<ExplainableRiskFactor> factors = new ArrayList<>();

        if (velocityExceeded || v[3] >= 0.50) {
            factors.add(new ExplainableRiskFactor(
                    "f3_velocityBurstScore",
                    "Rolling 60s Transaction Velocity",
                    v[3],
                    velocityExceeded ? "CRITICAL" : "HIGH",
                    String.format("User initiated %d transaction attempt(s) inside the 60s sliding window (threshold: %d).",
                            ctx.velocityCount(), properties.getRisk().getVelocityMaxAttempts())
            ));
        }

        if (v[0] >= 0.65) {
            factors.add(new ExplainableRiskFactor(
                    "f0_normalizedAmount",
                    "Elevated Transfer Amount",
                    v[0],
                    v[0] >= 0.85 ? "HIGH" : "MEDIUM",
                    String.format("Transfer amount %s %s translates to normalized log-magnitude %.2f.",
                            ctx.amount().toPlainString(), ctx.currency(), v[0])
            ));
        }

        if (v[1] >= 0.45) {
            factors.add(new ExplainableRiskFactor(
                    "f1_crossBorderIpAnomaly",
                    "Cross-Border / Geodesic IP Anomaly",
                    v[1],
                    v[1] >= 0.75 ? "HIGH" : "MEDIUM",
                    String.format("Corridor %s -> %s with %.0f km IP geodesic distance.",
                            ctx.sourceCountry(), ctx.destinationCountry(), ctx.ipDistanceKm())
            ));
        }

        if (v[2] >= 0.70) {
            factors.add(new ExplainableRiskFactor(
                    "f2_merchantRiskCategory",
                    "High-Risk Counterparty Category",
                    v[2],
                    v[2] >= 0.88 ? "HIGH" : "MEDIUM",
                    String.format("Counterparty MCC category '%s' carries risk weight %.2f.",
                            ctx.merchantCategory(), v[2])
            ));
        }

        if (v[6] >= 0.45) {
            factors.add(new ExplainableRiskFactor(
                    "f6_untrustedDeviceAnomaly",
                    "Unverified Device Fingerprint",
                    v[6],
                    v[6] >= 0.75 ? "HIGH" : "MEDIUM",
                    String.format("Session device trust score %.2f yields device anomaly indicator %.2f.",
                            ctx.deviceTrustScore(), v[6])
            ));
        }

        if (v[7] >= 0.70) {
            factors.add(new ExplainableRiskFactor(
                    "f7_newAccountImmaturity",
                    "Newly Provisioned Account",
                    v[7],
                    "MEDIUM",
                    String.format("Account age (%d days) is below 90-day maturity benchmark.", ctx.accountAgeDays())
            ));
        }

        if (factors.isEmpty()) {
            factors.add(new ExplainableRiskFactor(
                    "baseline_normal",
                    "Normal Behavioral Telemetry",
                    v[0],
                    "LOW",
                    "All 8 behavioral dimensions are within normal domestic baseline tolerances."
            ));
        }

        return factors;
    }

    private double computeRuleFloor(double[] v, boolean velocityExceeded) {
        if (velocityExceeded) {
            return 0.92;
        }
        // Weighted linear combination of normalized features as deterministic floor
        double weighted = (0.20 * v[0])
                + (0.18 * v[1])
                + (0.18 * v[2])
                + (0.16 * v[3])
                + (0.06 * v[4])
                + (0.08 * v[5])
                + (0.08 * v[6])
                + (0.06 * v[7]);
        return Math.min(0.99, Math.max(0.0, weighted));
    }

    public static double encodeMerchantCategoryRisk(String category) {
        if (category == null) {
            return 0.25;
        }
        return switch (category.trim().toUpperCase()) {
            case "UTILITIES", "GROCERY", "SAAS_SUBSCRIPTION", "HEALTHCARE" -> 0.10;
            case "RETAIL", "DINING", "TRAVEL", "EDUCATION" -> 0.25;
            case "ELECTRONICS", "PEER_TO_PEER", "MARKETPLACE" -> 0.45;
            case "LUXURY_GOODS", "HIGH_VALUE_RETAIL", "JEWELRY" -> 0.72;
            case "GAMBLING", "OFFSHORE_WIRE", "MONEY_REMITTANCE" -> 0.88;
            case "CRYPTO_EXCHANGE", "DIGITAL_ASSETS" -> 0.95;
            default -> 0.30;
        };
    }

    public List<SyntheticFraudPattern> getSyntheticPatterns() {
        return SYNTHETIC_PATTERNS;
    }

    public boolean isOpenSearchHealthy() {
        if (forceUnavailableForTest || !properties.getOpensearch().isEnabled()) {
            return false;
        }
        try {
            HttpRequest req = requestBuilder(buildOpenSearchBaseUrl())
                    .timeout(Duration.ofMillis(1200))
                    .GET()
                    .build();
            HttpResponse<Void> res = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
            boolean ok = res.statusCode() >= 200 && res.statusCode() < 300;
            openSearchHealthy.set(ok);
            return ok;
        } catch (Exception ex) {
            openSearchHealthy.set(false);
            return false;
        }
    }

    private double[] findSyntheticVectorById(String caseId) {
        for (SyntheticFraudPattern p : SYNTHETIC_PATTERNS) {
            if (p.caseId().equalsIgnoreCase(caseId)) {
                return p.vector();
            }
        }
        return SYNTHETIC_PATTERNS.get(0).vector();
    }

    private String buildOpenSearchBaseUrl() {
        AppProperties.OpenSearch os = properties.getOpensearch();
        return os.getScheme() + "://" + os.getHost() + ":" + os.getPort();
    }

    private HttpRequest.Builder requestBuilder(String uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(Duration.ofMillis(2000));
        String user = properties.getOpensearch().getUsername();
        String pass = properties.getOpensearch().getPassword();
        if (user != null && !user.isBlank() && pass != null) {
            String basic = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + basic);
        }
        return builder;
    }

    private static double l2Norm(double[] v) {
        double sum = 0.0;
        for (double d : v) {
            sum += d * d;
        }
        return Math.sqrt(sum);
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    public void setForceUnavailableForTest(boolean forceUnavailableForTest) {
        this.forceUnavailableForTest = forceUnavailableForTest;
    }

    public void setSimulateEmptyIndexForTest(boolean simulateEmptyIndexForTest) {
        this.simulateEmptyIndexForTest = simulateEmptyIndexForTest;
    }
}
