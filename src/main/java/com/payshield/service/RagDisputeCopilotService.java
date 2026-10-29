package com.payshield.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payshield.config.AppProperties;
import com.payshield.domain.PaymentStatus;
import com.payshield.domain.PaymentTransaction;
import com.payshield.exception.ApiException;
import com.payshield.repository.PaymentTransactionRepository;
import com.payshield.security.AuthenticatedUser;
import com.payshield.service.FraudVectorEngine.ExplainableRiskFactor;
import com.payshield.service.FraudVectorEngine.SimilarCaseMatch;
import com.payshield.service.RedisRiskEngine.RagCacheLookup;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * AI Dispute Copilot & Retrieval-Augmented Generation (RAG) Investigation Service.
 *
 * Capabilities:
 * 1. Retrieves top-K historical behavioral cases from OpenSearch (or deterministic k-NN fallback).
 * 2. Retrieves versioned compliance policies from classpath:policies/compliance-policies-v2.4.json.
 * 3. Redacts PII (emails, account identifiers) before any external LLM API request.
 * 4. Supports OpenAI-compatible (/chat/completions) and Google Gemini (:generateContent) adapters.
 * 5. Falls back deterministically when no API key is configured or provider errors/timeouts occur,
 *    explicitly labeling offline reports as HEURISTIC_OFFLINE_FALLBACK (not LLM-generated).
 * 6. Caches reports in Redis using a context-aware SHA-256 signature with a 6-hour TTL.
 */
@Service
public class RagDisputeCopilotService {

    private static final Logger log = LoggerFactory.getLogger(RagDisputeCopilotService.class);
    public static final String POLICY_CORPUS_VERSION = "2.4.0";

    public record PolicyDocument(
            String policyId,
            String version,
            String section,
            String title,
            String category,
            List<String> keywords,
            String summary,
            String complianceRequirement
    ) {
    }

    public record StructuredThreePointAnalysis(
            String point1RiskSignalsAndEvidence,
            String point2RelevantPolicyAndCompliance,
            String point3RecommendedNextActionAndLimitations
    ) {
    }

    public record DisputeAuditReportResponse(
            UUID transactionId,
            String transactionStatus,
            double riskScore,
            String cacheStatus,
            String cacheBackend,
            String cacheSignatureSha256,
            String generationMode,
            boolean llmGenerated,
            String modelIdentifier,
            String policyCorpusVersion,
            String redactedContextSummary,
            List<SimilarCaseMatch> retrievedHistoricalCases,
            List<PolicyDocument> retrievedPolicies,
            List<String> citedCaseIds,
            List<String> citedPolicyIds,
            StructuredThreePointAnalysis threePointReport,
            String methodologyAndUncertaintyNotice,
            Instant generatedAt
    ) {
    }

    private final PaymentTransactionRepository paymentRepository;
    private final PaymentLedgerService paymentLedgerService;
    private final FraudVectorEngine fraudVectorEngine;
    private final RedisRiskEngine redisRiskEngine;
    private final SecurityAuditService auditService;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private List<PolicyDocument> policyCorpus = new ArrayList<>();

    public RagDisputeCopilotService(PaymentTransactionRepository paymentRepository,
                                    PaymentLedgerService paymentLedgerService,
                                    FraudVectorEngine fraudVectorEngine,
                                    RedisRiskEngine redisRiskEngine,
                                    SecurityAuditService auditService,
                                    AppProperties properties,
                                    ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.paymentLedgerService = paymentLedgerService;
        this.fraudVectorEngine = fraudVectorEngine;
        this.redisRiskEngine = redisRiskEngine;
        this.auditService = auditService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(2, properties.getAi().getTimeoutSeconds())))
                .build();
    }

    @PostConstruct
    public void loadPolicyCorpus() {
        try (InputStream is = new ClassPathResource("policies/compliance-policies-v2.4.json").getInputStream()) {
            this.policyCorpus = objectMapper.readValue(is, new TypeReference<List<PolicyDocument>>() {});
            log.info("Loaded {} versioned compliance policy documents (v{})", policyCorpus.size(), POLICY_CORPUS_VERSION);
        } catch (Exception ex) {
            log.error("Failed to load compliance policy corpus", ex);
            this.policyCorpus = List.of();
        }
    }

    public DisputeAuditReportResponse runDisputeRagAudit(AuthenticatedUser actor,
                                                         UUID transactionId,
                                                         String ipAddress) {
        PaymentTransaction tx = paymentRepository.findById(transactionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND",
                        "Payment transaction not found."));

        paymentLedgerService.enforceTransactionReadAuthorization(actor, tx);

        String configuredProvider = resolveEffectiveProviderMode();
        String cacheSignature = redisRiskEngine.computeRagCacheSignature(
                tx.getId(),
                tx.getStatus().name(),
                tx.getRiskScore(),
                tx.getAnalystDecision(),
                POLICY_CORPUS_VERSION,
                configuredProvider
        );

        RagCacheLookup cacheLookup = redisRiskEngine.getCachedRagReport(cacheSignature);
        if (cacheLookup.hit() && cacheLookup.payloadJson() != null) {
            try {
                DisputeAuditReportResponse cached = objectMapper.readValue(
                        cacheLookup.payloadJson(), DisputeAuditReportResponse.class);
                return new DisputeAuditReportResponse(
                        cached.transactionId(),
                        cached.transactionStatus(),
                        cached.riskScore(),
                        "CACHE_HIT",
                        cacheLookup.cacheBackend(),
                        cacheSignature,
                        cached.generationMode(),
                        cached.llmGenerated(),
                        cached.modelIdentifier(),
                        cached.policyCorpusVersion(),
                        cached.redactedContextSummary(),
                        cached.retrievedHistoricalCases(),
                        cached.retrievedPolicies(),
                        cached.citedCaseIds(),
                        cached.citedPolicyIds(),
                        cached.threePointReport(),
                        cached.methodologyAndUncertaintyNotice(),
                        cached.generatedAt()
                );
            } catch (Exception ex) {
                log.warn("Failed to deserialize cached RAG report; regenerating", ex);
            }
        }

        // 1. Retrieve top-K similar historical cases
        double[] vector = parseVector(tx.getFeatureVectorJson());
        List<ExplainableRiskFactor> riskFactors = parseFactors(tx.getRiskFactorsJson());
        List<SimilarCaseMatch> retrievedCases = parseCases(tx.getMatchedCasesJson());
        if (retrievedCases.isEmpty()) {
            retrievedCases = fraudVectorEngine.computeDeterministicFallbackMatches(
                    vector, 3, "DETERMINISTIC_HEURISTIC_FALLBACK");
        }

        // 2. Retrieve applicable versioned compliance policies based on transaction telemetry & matched cases
        List<PolicyDocument> applicablePolicies = retrieveApplicablePolicies(tx, vector, retrievedCases);

        List<String> citedCaseIds = retrievedCases.stream().map(SimilarCaseMatch::caseId).distinct().toList();
        List<String> citedPolicyIds = applicablePolicies.stream().map(PolicyDocument::policyId).distinct().toList();

        // 3. Build PII-redacted context summary before any external LLM API call
        String redactedSummary = buildRedactedContextSummary(tx, vector, retrievedCases, applicablePolicies);

        // 4. Generate 3-point investigation report (External LLM if configured & reachable, otherwise deterministic offline fallback)
        GenerationOutcome outcome = generateThreePointAnalysis(tx, vector, riskFactors, retrievedCases, applicablePolicies, redactedSummary);

        DisputeAuditReportResponse report = new DisputeAuditReportResponse(
                tx.getId(),
                tx.getStatus().name(),
                tx.getRiskScore(),
                "CACHE_MISS",
                cacheLookup.cacheBackend(),
                cacheSignature,
                outcome.generationMode(),
                outcome.llmGenerated(),
                outcome.modelIdentifier(),
                POLICY_CORPUS_VERSION,
                redactedSummary,
                retrievedCases,
                applicablePolicies,
                citedCaseIds,
                citedPolicyIds,
                outcome.analysis(),
                "Evidence is retrieved from synthetic vector patterns (" + String.join(", ", citedCaseIds) +
                ") and local compliance corpus v" + POLICY_CORPUS_VERSION + " (" + String.join(", ", citedPolicyIds) +
                "). Similarity scores are heuristic indicators (0.00–0.99), not calibrated probabilities.",
                Instant.now()
        );

        try {
            String serialized = objectMapper.writeValueAsString(report);
            redisRiskEngine.putCachedRagReport(tx.getId(), cacheSignature, serialized);
        } catch (Exception ex) {
            log.warn("Failed to cache RAG report for tx={}", tx.getId(), ex);
        }

        auditService.recordEvent(
                actor.userId(),
                actor.email(),
                "RAG_DISPUTE_AUDIT_GENERATED",
                "PAYMENT_TRANSACTION",
                tx.getId().toString(),
                ipAddress,
                "Mode=" + outcome.generationMode() + " cacheSignature=" + cacheSignature.substring(0, 12)
        );

        return report;
    }

    public List<PolicyDocument> retrieveApplicablePolicies(PaymentTransaction tx,
                                                           double[] vector,
                                                           List<SimilarCaseMatch> matchedCases) {
        Set<String> matchedPolicyIds = new LinkedHashSet<>();

        // Always include double-entry ledger & settlement policy
        matchedPolicyIds.add("POL-LED-601");

        if (vector.length >= 8) {
            if (vector[0] >= 0.45 || tx.getAmount().doubleValue() >= 2500.0) {
                matchedPolicyIds.add("POL-EFT-101");
            }
            if (vector[3] >= 0.40 || tx.getVelocityCount() > 3) {
                matchedPolicyIds.add("POL-VEL-204");
            }
            if (vector[1] >= 0.45 || tx.getIpDistanceKm() >= 1500.0) {
                matchedPolicyIds.add("POL-GEO-302");
            }
            if (vector[2] >= 0.70) {
                matchedPolicyIds.add("POL-MCC-409");
            }
            if (vector[6] >= 0.40 || vector[4] >= 0.70) {
                matchedPolicyIds.add("POL-ATO-501");
            }
        }

        for (SimilarCaseMatch c : matchedCases) {
            if (c.policyReference() != null) {
                for (String ref : c.policyReference().split(",")) {
                    String trimmed = ref.trim();
                    if (!trimmed.isEmpty()) {
                        matchedPolicyIds.add(trimmed);
                    }
                }
            }
        }

        List<PolicyDocument> selected = new ArrayList<>();
        for (PolicyDocument doc : policyCorpus) {
            if (matchedPolicyIds.contains(doc.policyId())) {
                selected.add(doc);
            }
        }
        if (selected.isEmpty() && !policyCorpus.isEmpty()) {
            selected.add(policyCorpus.get(0));
        }
        return selected;
    }

    /**
     * Redacts PII (user emails, wallet UUIDs, raw IP addresses) before building prompts for external LLM providers.
     */
    public static String redactEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "[REDACTED_USER]";
        }
        String[] parts = email.split("@", 2);
        String local = parts[0];
        String maskedLocal = local.length() <= 2 ? "**" : local.substring(0, 2) + "***";
        return maskedLocal + "@" + parts[1];
    }

    private String buildRedactedContextSummary(PaymentTransaction tx,
                                               double[] vector,
                                               List<SimilarCaseMatch> cases,
                                               List<PolicyDocument> policies) {
        String senderMasked = redactEmail(tx.getSenderEmail());
        String recipientMasked = redactEmail(tx.getRecipientEmail());
        List<String> caseRefs = cases.stream()
                .map(c -> c.caseId() + "(sim=" + c.rawCosineSimilarity() + ",risk=" + c.normalizedRiskContribution() + ")")
                .toList();
        List<String> policyRefs = policies.stream().map(PolicyDocument::policyId).toList();

        return String.format(
                "RedactedTx[sender=%s, recipient=%s, amount=%s %s, mcc=%s, corridor=%s->%s, ipDistKm=%.0f, velocity=%d, status=%s, riskScore=%.4f, cases=%s, policies=%s]",
                senderMasked,
                recipientMasked,
                tx.getAmount().toPlainString(),
                tx.getCurrency(),
                tx.getMerchantCategory(),
                tx.getSourceCountry(),
                tx.getDestinationCountry(),
                tx.getIpDistanceKm(),
                tx.getVelocityCount(),
                tx.getStatus(),
                tx.getRiskScore(),
                caseRefs,
                policyRefs
        );
    }

    private record GenerationOutcome(
            StructuredThreePointAnalysis analysis,
            String generationMode,
            boolean llmGenerated,
            String modelIdentifier
    ) {
    }

    private GenerationOutcome generateThreePointAnalysis(PaymentTransaction tx,
                                                         double[] vector,
                                                         List<ExplainableRiskFactor> factors,
                                                         List<SimilarCaseMatch> cases,
                                                         List<PolicyDocument> policies,
                                                         String redactedSummary) {
        String provider = resolveEffectiveProviderMode();
        String apiKey = properties.getAi().getApiKey();

        if (!"OFFLINE".equalsIgnoreCase(provider) && apiKey != null && !apiKey.isBlank()) {
            try {
                if ("GEMINI".equalsIgnoreCase(provider)) {
                    StructuredThreePointAnalysis geminiResult = callGeminiAdapter(redactedSummary, cases, policies);
                    return new GenerationOutcome(geminiResult, "LLM_GEMINI_ADAPTER", true, properties.getAi().getModel());
                } else if ("OPENAI_COMPATIBLE".equalsIgnoreCase(provider)) {
                    StructuredThreePointAnalysis openAiResult = callOpenAiCompatibleAdapter(redactedSummary, cases, policies);
                    return new GenerationOutcome(openAiResult, "LLM_OPENAI_COMPATIBLE", true, properties.getAi().getModel());
                }
            } catch (Exception ex) {
                log.warn("External AI provider '{}' failed or timed out ({}); falling back to deterministic heuristic report",
                        provider, ex.getMessage());
            }
        }

        StructuredThreePointAnalysis offlineAnalysis = buildDeterministicHeuristicAnalysis(tx, vector, factors, cases, policies);
        return new GenerationOutcome(
                offlineAnalysis,
                "HEURISTIC_OFFLINE_FALLBACK",
                false,
                "deterministic-policy-engine-v2.4"
        );
    }

    private StructuredThreePointAnalysis buildDeterministicHeuristicAnalysis(PaymentTransaction tx,
                                                                             double[] vector,
                                                                             List<ExplainableRiskFactor> factors,
                                                                             List<SimilarCaseMatch> cases,
                                                                             List<PolicyDocument> policies) {
        SimilarCaseMatch primaryCase = cases.isEmpty() ? null : cases.get(0);
        String caseCitations = cases.stream()
                .map(c -> String.format("%s (%s, rawCosine=%.4f, normalizedScore=%.4f)",
                        c.caseId(), c.typology(), c.rawCosineSimilarity(), c.normalizedRiskContribution()))
                .reduce((a, b) -> a + "; " + b)
                .orElse("None");

        String factorSummary = factors.stream()
                .map(f -> String.format("%s [%s: %.2f] — %s", f.label(), f.severity(), f.featureValue(), f.explanation()))
                .reduce((a, b) -> a + " | " + b)
                .orElse("Baseline telemetry within normal parameters.");

        String point1 = String.format(
                "[HEURISTIC OFFLINE ANALYSIS] Transaction %s (%s %s at %s, MCC: %s) recorded composite heuristic risk score %.4f (status: %s, scoring source: %s). " +
                "Observed risk signals: %s. Top retrieved synthetic reference patterns: %s.",
                tx.getId(), tx.getAmount().toPlainString(), tx.getCurrency(), tx.getMerchantName(),
                tx.getMerchantCategory(), tx.getRiskScore(), tx.getStatus(), tx.getScoringSource(),
                factorSummary, caseCitations
        );

        String policyCitations = policies.stream()
                .map(p -> String.format("%s (%s): %s", p.policyId(), p.section(), p.complianceRequirement()))
                .reduce((a, b) -> a + " || " + b)
                .orElse("POL-LED-601: Standard double-entry ledger verification.");

        String point2 = String.format(
                "Applicable compliance controls from local policy corpus v%s: %s",
                POLICY_CORPUS_VERSION, policyCitations
        );

        String recommendedAction;
        if (tx.getStatus() == PaymentStatus.FLAGGED_FRAUD) {
            recommendedAction = "Keep transfer quarantined in FLAGGED_FRAUD (no wallet funds debited per POL-LED-601). " +
                    "Require out-of-band customer step-up verification and session fingerprint validation before any manual APPROVE override; otherwise DECLINE.";
        } else if (tx.getStatus() == PaymentStatus.REVIEW_REQUIRED) {
            recommendedAction = "Conduct Risk Analyst secondary review of corridor (" + tx.getSourceCountry() + " -> " +
                    tx.getDestinationCountry() + ") and counterparty (" + tx.getMerchantName() + "). Approve only if customer confirms intent.";
        } else if (tx.getStatus() == PaymentStatus.DECLINED) {
            recommendedAction = "Transaction is terminally DECLINED. Maintain audit record and monitor account for further velocity bursts.";
        } else {
            recommendedAction = "Transaction settled in APPROVED status with verified zero-sum DEBIT/CREDIT ledger entries. No dispute intervention required unless customer reports unauthorized access.";
        }

        String point3 = String.format(
                "Recommended Action: %s Uncertainty & Limitations: This report was generated by the deterministic offline heuristic engine (no external LLM invoked). " +
                "Vector similarity scores against %s are heuristic indicators against 5 synthetic patterns, not calibrated fraud probabilities.",
                recommendedAction,
                primaryCase != null ? primaryCase.caseId() : "synthetic corpus"
        );

        return new StructuredThreePointAnalysis(point1, point2, point3);
    }

    private StructuredThreePointAnalysis callOpenAiCompatibleAdapter(String redactedSummary,
                                                                     List<SimilarCaseMatch> cases,
                                                                     List<PolicyDocument> policies) throws Exception {
        String endpoint = properties.getAi().getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
        String prompt = buildStrictJsonPrompt(redactedSummary, cases, policies);

        Map<String, Object> body = Map.of(
                "model", properties.getAi().getModel(),
                "temperature", 0.1,
                "messages", List.of(
                        Map.of("role", "system", "content",
                                "You are PayShield AI Dispute Copilot. Respond ONLY with a valid JSON object containing keys: point1RiskSignalsAndEvidence, point2RelevantPolicyAndCompliance, point3RecommendedNextActionAndLimitations. Never invent case IDs or policy IDs."),
                        Map.of("role", "user", "content", prompt)
                )
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(properties.getAi().getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.getAi().getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("OpenAI-compatible endpoint returned HTTP " + response.statusCode());
        }

        JsonNode root = objectMapper.readTree(response.body());
        String content = root.path("choices").path(0).path("message").path("content").asText();
        return parseModelJsonResponse(content);
    }

    private StructuredThreePointAnalysis callGeminiAdapter(String redactedSummary,
                                                           List<SimilarCaseMatch> cases,
                                                           List<PolicyDocument> policies) throws Exception {
        String model = properties.getAi().getModel();
        String baseUrl = properties.getAi().getBaseUrl().replaceAll("/+$", "");
        String endpoint = baseUrl + "/models/" + model + ":generateContent?key=" + properties.getAi().getApiKey();
        String prompt = buildStrictJsonPrompt(redactedSummary, cases, policies);

        Map<String, Object> body = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(Map.of("text", prompt)))
                )
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(properties.getAi().getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Gemini endpoint returned HTTP " + response.statusCode());
        }

        JsonNode root = objectMapper.readTree(response.body());
        String content = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText();
        return parseModelJsonResponse(content);
    }

    private String buildStrictJsonPrompt(String redactedSummary,
                                         List<SimilarCaseMatch> cases,
                                         List<PolicyDocument> policies) throws Exception {
        return "Analyze the following redacted transaction using ONLY the provided historical cases and compliance policies.\n" +
                "Redacted Transaction: " + redactedSummary + "\n" +
                "Retrieved Cases: " + objectMapper.writeValueAsString(cases) + "\n" +
                "Retrieved Policies: " + objectMapper.writeValueAsString(policies) + "\n" +
                "Return JSON with exact keys: point1RiskSignalsAndEvidence, point2RelevantPolicyAndCompliance, point3RecommendedNextActionAndLimitations.";
    }

    private StructuredThreePointAnalysis parseModelJsonResponse(String rawContent) throws Exception {
        if (rawContent == null || rawContent.isBlank()) {
            throw new IllegalArgumentException("Empty model response");
        }
        String cleaned = rawContent.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
        }
        JsonNode node = objectMapper.readTree(cleaned);
        String p1 = node.path("point1RiskSignalsAndEvidence").asText("");
        String p2 = node.path("point2RelevantPolicyAndCompliance").asText("");
        String p3 = node.path("point3RecommendedNextActionAndLimitations").asText("");
        if (p1.isBlank() || p2.isBlank() || p3.isBlank()) {
            throw new IllegalArgumentException("Model response missing required three-point fields");
        }
        return new StructuredThreePointAnalysis(p1, p2, p3);
    }

    private String resolveEffectiveProviderMode() {
        String provider = properties.getAi().getProvider();
        String apiKey = properties.getAi().getApiKey();
        if (provider == null || provider.isBlank() || "OFFLINE".equalsIgnoreCase(provider)
                || apiKey == null || apiKey.isBlank()) {
            return "OFFLINE";
        }
        return provider.trim().toUpperCase();
    }

    private double[] parseVector(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<double[]>() {});
        } catch (Exception e) {
            return new double[8];
        }
    }

    private List<ExplainableRiskFactor> parseFactors(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<ExplainableRiskFactor>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<SimilarCaseMatch> parseCases(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<SimilarCaseMatch>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<PolicyDocument> getPolicyCorpus() {
        return Collections.unmodifiableList(policyCorpus);
    }
}
