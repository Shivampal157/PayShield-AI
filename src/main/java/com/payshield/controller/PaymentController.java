package com.payshield.controller;

import com.payshield.domain.PaymentStatus;
import com.payshield.dto.PaymentLedgerDtos.OverviewMetricsResponse;
import com.payshield.dto.PaymentLedgerDtos.PaymentTransactionResponse;
import com.payshield.dto.PaymentLedgerDtos.ProcessPaymentRequest;
import com.payshield.security.AuthenticatedUser;
import com.payshield.service.PaymentLedgerService;
import com.payshield.service.RagDisputeCopilotService;
import com.payshield.service.RagDisputeCopilotService.DisputeAuditReportResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "2. Payments & AI Dispute Copilot", description = "Idempotent payment processing, transaction history, and RAG dispute audits")
public class PaymentController {

    private final PaymentLedgerService paymentLedgerService;
    private final RagDisputeCopilotService ragDisputeCopilotService;

    public PaymentController(PaymentLedgerService paymentLedgerService,
                             RagDisputeCopilotService ragDisputeCopilotService) {
        this.paymentLedgerService = paymentLedgerService;
        this.ragDisputeCopilotService = ragDisputeCopilotService;
    }

    @PostMapping("/process")
    @Operation(summary = "Process a payment transfer with Idempotency-Key deduplication, 8-D fraud scoring, and atomic double-entry ledger")
    public ResponseEntity<PaymentTransactionResponse> processPayment(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Parameter(description = "Unique client-generated Idempotency-Key (UUID recommended)", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ProcessPaymentRequest request,
            HttpServletRequest httpRequest) {

        PaymentTransactionResponse response = paymentLedgerService.processPayment(
                user,
                idempotencyKey,
                request,
                AuthController.resolveClientIp(httpRequest)
        );
        HttpStatus status = response.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping
    @Operation(summary = "List payment transactions with pagination, sorting, status, currency, and search filtering")
    public ResponseEntity<Page<PaymentTransactionResponse>> listPayments(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String direction) {

        Sort.Direction dir = "ASC".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)), Sort.by(dir, sortBy));
        return ResponseEntity.ok(paymentLedgerService.listTransactions(user, status, currency, search, pageable));
    }

    @GetMapping("/overview")
    @Operation(summary = "Get aggregated overview metrics, wallet balances, status breakdown, and recent activity")
    public ResponseEntity<OverviewMetricsResponse> getOverview(@AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok(paymentLedgerService.getOverviewMetrics(user));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get payment transaction details, 8-D feature vector, risk factors, and immutable ledger entries")
    public ResponseEntity<PaymentTransactionResponse> getPaymentById(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id) {
        return ResponseEntity.ok(paymentLedgerService.getTransactionById(user, id));
    }

    @PostMapping("/{id}/dispute-rag-audit")
    @Operation(summary = "Run AI Dispute Copilot RAG investigation on a transaction with SHA-256 Redis caching")
    public ResponseEntity<DisputeAuditReportResponse> runDisputeRagAudit(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ragDisputeCopilotService.runDisputeRagAudit(
                user, id, AuthController.resolveClientIp(httpRequest)));
    }
}
