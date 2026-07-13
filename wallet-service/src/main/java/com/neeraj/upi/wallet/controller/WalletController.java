package com.neeraj.upi.wallet.controller;

import com.neeraj.upi.common.dto.ApiResponse;
import com.neeraj.upi.wallet.dto.AddMoneyRequest;
import com.neeraj.upi.wallet.dto.LedgerResponse;
import com.neeraj.upi.wallet.dto.TransferRequest;
import com.neeraj.upi.wallet.dto.WalletResponse;
import com.neeraj.upi.wallet.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/wallet")
@RequiredArgsConstructor
@Tag(name = "Wallet", description = "Balance, top-up, and internal transfer")
public class WalletController {

    private final WalletService walletService;

    @GetMapping("/balance/{upiId}")
    @Operation(summary = "Get wallet balance for a UPI ID")
    public ResponseEntity<ApiResponse<WalletResponse>> getBalance(@PathVariable String upiId) {
        return ResponseEntity.ok(ApiResponse.ok(walletService.getBalance(upiId)));
    }

    @PostMapping("/add-money/{upiId}")
    @Operation(summary = "Mock bank top-up — credit wallet directly")
    public ResponseEntity<ApiResponse<WalletResponse>> addMoney(
            @PathVariable String upiId,
            @Valid @RequestBody AddMoneyRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(walletService.addMoney(upiId, request)));
    }

    @PostMapping("/internal/transfer")
    @Operation(summary = "[INTERNAL] Atomic debit/credit between two wallets")
    public ResponseEntity<ApiResponse<Void>> transfer(@Valid @RequestBody TransferRequest request) {
        walletService.transfer(request);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @GetMapping("/ledger/{upiId}")
    @Operation(summary = "Get paginated ledger (transaction history) for a wallet")
    public ResponseEntity<ApiResponse<Page<LedgerResponse>>> getLedger(
            @PathVariable String upiId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(walletService.getLedger(upiId, page, size)));
    }
}
