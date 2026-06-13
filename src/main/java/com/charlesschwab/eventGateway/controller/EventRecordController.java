package com.charlesschwab.eventGateway.controller;

import com.charlesschwab.eventGateway.model.EventRecord;
import com.charlesschwab.eventGateway.repository.EventRecordRepository;
import com.charlesschwab.eventGateway.service.AccountServiceClient;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/events")
public class EventRecordController {

    private final EventRecordRepository repository;
    private final AccountServiceClient accountClient;
    private final Counter accountFallbackCounter;

    public EventRecordController(EventRecordRepository repository, AccountServiceClient accountClient,
                                 MeterRegistry registry) {
        this.repository = repository;
        this.accountClient = accountClient;
        this.accountFallbackCounter = Counter.builder("events.accountService.fallback")
                .description("Number of times account service fallback was used")
                .register(registry);
    }

    // GET /events?account={accountId}  -> list events for account sorted by eventTimestamp (desc)
    @GetMapping
    public List<EventRecord> listByAccount(@RequestParam(name = "account", required = false) String account) {
        if (account != null && !account.isBlank()) {
            return repository.findByAccountIdOrderByEventTimestampDesc(account);
        }
        return repository.findAllByOrderByEventTimestampDesc();
    }

    // GET /events/{id} -> fetch single event by id
    @GetMapping("/{id}")
    public ResponseEntity<EventRecord> get(@PathVariable String id) {
        return repository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // POST left in place for convenience but not used for the graceful-degradation guarantee
    @PostMapping
    public ResponseEntity<EventRecord> create(@RequestBody EventRecord record) {
        // validate required fields
        if (record.getAccountId() == null || record.getAccountId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        // if eventId provided and already exists, return existing (200)
        if (record.getEventId() != null && !record.getEventId().isBlank()) {
            return repository.findById(record.getEventId())
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> applyThenPersist(record));
        }

        // otherwise, apply (call Account Service) then persist
        return applyThenPersist(record);
    }

    private ResponseEntity<EventRecord> applyThenPersist(EventRecord record) {
        // ensure id and timestamp
        if (record.getEventId() == null || record.getEventId().isBlank()) {
            record.setEventId(java.util.UUID.randomUUID().toString());
        }
        if (record.getEventTimestamp() == null) {
            record.setEventTimestamp(java.time.Instant.now());
        }

        // Call Account Service to validate that account exists via Resilience4j-wrapped async call.
        try {
            accountClient.validateAccount(record.getAccountId()).join();

            // Extract amount from metadata if present (fallback to ZERO)
            java.math.BigDecimal amount = java.math.BigDecimal.ZERO;
            if (record.getMetadata() != null && record.getMetadata().get("amount") != null) {
                Object amtObj = record.getMetadata().get("amount");
                try {
                    if (amtObj instanceof Number) {
                        amount = java.math.BigDecimal.valueOf(((Number) amtObj).doubleValue());
                    } else {
                        amount = new java.math.BigDecimal(amtObj.toString());
                    }
                } catch (Exception ignored) {
                    amount = java.math.BigDecimal.ZERO;
                }
            }

            // Apply the transaction on the Account Service before persisting locally
            accountClient.applyTransaction(record.getEventId(), record.getAccountId(), amount).join();

        } catch (Exception ex) {
            // If circuit is open or timeout/exception occurred, count fallback and return 503
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof CallNotPermittedException || cause instanceof java.util.concurrent.TimeoutException
                    || cause instanceof RuntimeException) {
                accountFallbackCounter.increment();
                return ResponseEntity.status(503).build();
            }
            throw ex;
        }

        EventRecord saved = repository.save(record);
        return ResponseEntity.created(URI.create("/events/" + saved.getEventId())).body(saved);
    }
}

