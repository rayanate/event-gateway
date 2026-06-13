package com.charlesschwab.eventGateway.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.CompletableFuture;
import java.math.BigDecimal;
import java.util.Map;

@Component
public class AccountServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AccountServiceClient.class);

    private final RestTemplate restTemplate;
    private final String accountServiceUrl;

    public AccountServiceClient(RestTemplate restTemplate, @Value("${account.service.url}") String accountServiceUrl) {
        this.restTemplate = restTemplate;
        this.accountServiceUrl = accountServiceUrl;
    }

    @CircuitBreaker(name = "accountService", fallbackMethod = "accountFallback")
    @TimeLimiter(name = "accountService", fallbackMethod = "accountFallback")
    public CompletableFuture<Void> validateAccount(String accountId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = accountServiceUrl + "/accounts/" + accountId;
            log.debug("Calling Account Service at {}", url);
            restTemplate.getForEntity(url, Void.class);
            return null;
        });
    }

    @CircuitBreaker(name = "accountService", fallbackMethod = "accountTxFallback")
    @TimeLimiter(name = "accountService", fallbackMethod = "accountTxFallback")
    public CompletableFuture<Void> applyTransaction(String eventId, String accountId, BigDecimal amount) {
        return CompletableFuture.runAsync(() -> {
            String url = accountServiceUrl + "/accounts/" + accountId + "/transactions";
            log.debug("Posting transaction to Account Service at {}", url);
            Map<String, Object> req = Map.of("eventId", eventId, "amount", amount);
            restTemplate.postForEntity(url, req, Void.class);
        });
    }

    // fallback for both circuit breaker and time limiter. return a failed future to signal failure upstream
    private CompletableFuture<Void> accountFallback(String accountId, Throwable ex) {
        log.warn("Account Service validation failed for {}: {}", accountId, ex.toString());
        return CompletableFuture.failedFuture(new RuntimeException("account-service-unavailable", ex));
    }

    private CompletableFuture<Void> accountTxFallback(String eventId, String accountId, BigDecimal amount, Throwable ex) {
        log.warn("Account Service transaction failed for {} account {}: {}", eventId, accountId, ex.toString());
        return CompletableFuture.failedFuture(new RuntimeException("account-service-unavailable", ex));
    }
}

