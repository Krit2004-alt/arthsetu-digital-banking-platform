package com.fincore.transaction.infrastructure.config;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@FeignClient(name = "account-service", url = "${fincore.services.account-service.url}")
public interface AccountServiceFeignClient {

    @PostMapping("/api/v1/accounts/{accountId}/validate-balance")
    void validateBalance(@PathVariable UUID accountId,
                         @RequestBody Map<String, Object> request);

    @GetMapping("/api/v1/accounts/{accountId}/verify-ownership")
    void verifyOwnership(@PathVariable UUID accountId,
                         @RequestHeader("X-User-Id") String userId);

    @PostMapping("/api/v1/accounts/{accountId}/credit")
    void credit(@PathVariable UUID accountId,
                @RequestBody Map<String, Object> request);
}