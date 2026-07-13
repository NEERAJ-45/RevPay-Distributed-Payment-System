package com.neeraj.upi.transaction.controller;

import com.neeraj.upi.common.dto.ApiResponse;
import com.neeraj.upi.transaction.dto.PayRequest;
import com.neeraj.upi.transaction.dto.PayResponse;
import com.neeraj.upi.transaction.service.TransactionService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/transactions")
@RequiredArgsConstructor
@Tag(name = "Transactions", description = "P2P payments, status check, history")
@SecurityRequirement(name = "bearerAuth")
public class PayController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final TransactionService transactionService;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @PostMapping("/pay")
    @Operation(summary = "Send money to a UPI ID")
    public ResponseEntity<ApiResponse<PayResponse>> pay(
            @Valid @RequestBody PayRequest request,
            @RequestHeader("Authorization") String authHeader) {
        String senderUpiId = extractUpiId(authHeader);
        PayResponse response = transactionService.pay(request, senderUpiId);
        HttpStatus status = response.isReplayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(ApiResponse.ok(response));
    }

    @GetMapping("/{txnId}")
    @Operation(summary = "Get transaction status by ID")
    public ResponseEntity<ApiResponse<PayResponse>> getById(@PathVariable UUID txnId) {
        return ResponseEntity.ok(ApiResponse.ok(transactionService.getById(txnId)));
    }

    @GetMapping("/history/{upiId}")
    @Operation(summary = "Get paginated transaction history for a UPI ID")
    public ResponseEntity<ApiResponse<Page<PayResponse>>> getHistory(
            @PathVariable String upiId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(transactionService.getHistory(upiId, page, size)));
    }

    private String extractUpiId(String authHeader) {
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            throw new IllegalArgumentException("Missing or invalid Authorization header");
        }
        String token = authHeader.substring(BEARER_PREFIX.length());
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        return claims.get("upiId", String.class);
    }
}
