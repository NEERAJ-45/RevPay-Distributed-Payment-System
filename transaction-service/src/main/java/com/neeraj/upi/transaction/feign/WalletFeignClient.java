package com.neeraj.upi.transaction.feign;

import com.neeraj.upi.common.dto.ApiResponse;
import com.neeraj.upi.transaction.dto.TransferRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * OpenFeign client — calls wallet-service INTERNAL endpoints directly.
 * Does NOT go through the API Gateway (internal service-to-service call).
 * URL is configured via wallet.service.url in application.yml
 */
@FeignClient(name = "wallet-service", url = "${wallet.service.url}")
public interface WalletFeignClient {

    /** Get wallet balance by UPI ID */
    @GetMapping("/wallet/balance/{upiId}")
    ApiResponse<Map<String, Object>> getBalance(@PathVariable String upiId);

    /** Trigger atomic debit+credit between two wallets */
    @PostMapping("/wallet/internal/transfer")
    ApiResponse<Void> transfer(@RequestBody TransferRequest transferRequest);
}
