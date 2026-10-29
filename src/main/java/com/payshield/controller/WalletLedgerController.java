package com.payshield.controller;

import com.payshield.domain.LedgerEntryType;
import com.payshield.domain.UserAccount;
import com.payshield.dto.PaymentLedgerDtos.LedgerEntryResponse;
import com.payshield.dto.PaymentLedgerDtos.ReconciliationReportResponse;
import com.payshield.dto.PaymentLedgerDtos.WalletResponse;
import com.payshield.repository.UserAccountRepository;
import com.payshield.repository.WalletRepository;
import com.payshield.security.AuthenticatedUser;
import com.payshield.service.PaymentLedgerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "3. Wallets & Double-Entry Ledger", description = "Customer wallets, immutable ledger entries, and zero-sum reconciliation")
public class WalletLedgerController {

    private final PaymentLedgerService paymentLedgerService;
    private final WalletRepository walletRepository;
    private final UserAccountRepository userRepository;

    public WalletLedgerController(PaymentLedgerService paymentLedgerService,
                                  WalletRepository walletRepository,
                                  UserAccountRepository userRepository) {
        this.paymentLedgerService = paymentLedgerService;
        this.walletRepository = walletRepository;
        this.userRepository = userRepository;
    }

    @GetMapping("/wallets/me")
    @Operation(summary = "Get current authenticated user's currency-aware wallets and balances")
    public ResponseEntity<List<WalletResponse>> getMyWallets(@AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok(paymentLedgerService.getWalletsForUser(user.userId()));
    }

    @GetMapping("/wallets/directory")
    @Operation(summary = "List available recipient directory entries (account numbers & emails) for payment simulation")
    public ResponseEntity<List<Map<String, String>>> getRecipientDirectory(@AuthenticationPrincipal AuthenticatedUser user) {
        Map<UUID, UserAccount> usersById = new HashMap<>();
        userRepository.findAll().forEach(u -> usersById.put(u.getId(), u));

        List<Map<String, String>> directory = walletRepository.findAll().stream()
                .filter(w -> !w.getUserId().equals(user.userId()))
                .map(w -> {
                    UserAccount owner = usersById.get(w.getUserId());
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("accountNumber", w.getAccountNumber());
                    item.put("currency", w.getCurrency());
                    item.put("ownerEmail", owner != null ? owner.getEmail() : "unknown");
                    item.put("ownerName", owner != null ? owner.getFullName() : "Counterparty");
                    return item;
                })
                .toList();
        return ResponseEntity.ok(directory);
    }

    @GetMapping("/ledger")
    @Operation(summary = "List immutable double-entry ledger entries with filtering and pagination")
    public ResponseEntity<Page<LedgerEntryResponse>> listLedgerEntries(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) LedgerEntryType entryType,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) UUID transactionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size) {

        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.min(100, Math.max(1, size)),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );
        return ResponseEntity.ok(paymentLedgerService.listLedgerEntries(user, entryType, currency, transactionId, pageable));
    }

    @GetMapping("/ledger/reconciliation")
    @PreAuthorize("hasAnyRole('RISK_ANALYST', 'ADMIN')")
    @Operation(summary = "Verify double-entry ledger zero-sum reconciliation across all currencies and transactions")
    public ResponseEntity<ReconciliationReportResponse> reconcileLedger() {
        return ResponseEntity.ok(paymentLedgerService.reconcileLedger());
    }
}
