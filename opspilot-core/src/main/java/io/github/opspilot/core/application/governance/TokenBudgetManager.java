package io.github.opspilot.core.application.governance;

import io.github.opspilot.core.port.provider.ProviderContracts.ProviderUsage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Thread-safe reservation and reconciliation ledger for one incident budget. */
public final class TokenBudgetManager {
    public enum UsageSource {
        PROVIDER,
        CONSERVATIVE_ESTIMATE
    }

    public enum AttemptOutcome {
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

    public record BudgetLimits(
            long maxInputTokensPerCall,
            long maxOutputTokensPerCall,
            long maxAgentTokens,
            long maxTaskTokens,
            long maxIncidentTokens,
            int maxCalls,
            Long maxCostMicros) {
        public BudgetLimits {
            requirePositive(maxInputTokensPerCall, "maxInputTokensPerCall");
            requirePositive(maxOutputTokensPerCall, "maxOutputTokensPerCall");
            requirePositive(maxAgentTokens, "maxAgentTokens");
            requirePositive(maxTaskTokens, "maxTaskTokens");
            requirePositive(maxIncidentTokens, "maxIncidentTokens");
            requirePositive(maxCalls, "maxCalls");
            if (maxCostMicros != null) {
                requirePositive(maxCostMicros, "maxCostMicros");
            }
        }
    }

    public record UsageScope(String incidentId, String taskId, String agentId) {
        public UsageScope {
            incidentId = requireText(incidentId, "incidentId");
            taskId = requireText(taskId, "taskId");
            agentId = requireText(agentId, "agentId");
        }
    }

    public record CostReservation(Long costMicros, String priceTableVersion) {
        public CostReservation {
            if (costMicros != null && costMicros < 0) {
                throw new IllegalArgumentException("costMicros must not be negative");
            }
            if (costMicros != null) {
                priceTableVersion = requireText(priceTableVersion, "priceTableVersion");
            }
        }
    }

    public record ReservationRequest(
            UUID invocationId,
            UsageScope scope,
            long estimatedInputTokens,
            long reservedOutputTokens,
            CostReservation cost,
            Instant reservedAt) {
        public ReservationRequest {
            Objects.requireNonNull(invocationId, "invocationId");
            Objects.requireNonNull(scope, "scope");
            requireNonNegative(estimatedInputTokens, "estimatedInputTokens");
            requirePositive(reservedOutputTokens, "reservedOutputTokens");
            cost = cost == null ? new CostReservation(null, null) : cost;
            Objects.requireNonNull(reservedAt, "reservedAt");
        }

        long reservedTokens() {
            return Math.addExact(estimatedInputTokens, reservedOutputTokens);
        }
    }

    public record ConservativeUsage(long inputTokens, long outputTokens, String estimatorVersion) {
        public ConservativeUsage {
            requireNonNegative(inputTokens, "inputTokens");
            requirePositive(outputTokens, "outputTokens");
            estimatorVersion = requireText(estimatorVersion, "estimatorVersion");
        }
    }

    @FunctionalInterface
    public interface UsageEstimator {
        ConservativeUsage estimate(ReservationRequest reservation, AttemptOutcome outcome);
    }

    public record LedgerEntry(
            UUID invocationId,
            UsageScope scope,
            long inputTokens,
            long outputTokens,
            long cachedTokens,
            Long costMicros,
            boolean costEstimated,
            String priceTableVersion,
            UsageSource source,
            String estimatorVersion,
            AttemptOutcome outcome,
            Instant recordedAt) {
        public LedgerEntry {
            Objects.requireNonNull(invocationId, "invocationId");
            Objects.requireNonNull(scope, "scope");
            requireNonNegative(inputTokens, "inputTokens");
            requireNonNegative(outputTokens, "outputTokens");
            requireNonNegative(cachedTokens, "cachedTokens");
            if (costMicros != null && costMicros < 0) {
                throw new IllegalArgumentException("costMicros must not be negative");
            }
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(recordedAt, "recordedAt");
        }

        public long totalTokens() {
            return Math.addExact(inputTokens, outputTokens);
        }
    }

    public record BudgetSnapshot(
            long consumedTokens,
            long reservedTokens,
            int calls,
            Long consumedAndReservedCostMicros,
            List<LedgerEntry> ledgerEntries) {
        public BudgetSnapshot {
            ledgerEntries = List.copyOf(ledgerEntries);
        }
    }

    public static final class BudgetExceededException extends IllegalStateException {
        public BudgetExceededException(String code) {
            super(code);
        }
    }

    private final BudgetLimits limits;
    private final UsageEstimator estimator;
    private final Map<UUID, ReservationRequest> activeReservations = new LinkedHashMap<>();
    private final Map<UUID, LedgerEntry> ledger = new LinkedHashMap<>();

    public TokenBudgetManager(BudgetLimits limits, UsageEstimator estimator) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.estimator = Objects.requireNonNull(estimator, "estimator");
    }

    public synchronized ReservationRequest reserve(ReservationRequest request) {
        Objects.requireNonNull(request, "request");
        if (activeReservations.containsKey(request.invocationId()) || ledger.containsKey(request.invocationId())) {
            throw new IllegalStateException("INVOCATION_ALREADY_ACCOUNTED");
        }
        if (request.estimatedInputTokens() > limits.maxInputTokensPerCall()) {
            throw new BudgetExceededException("INPUT_TOKENS_PER_CALL_EXCEEDED");
        }
        if (request.reservedOutputTokens() > limits.maxOutputTokensPerCall()) {
            throw new BudgetExceededException("OUTPUT_TOKENS_PER_CALL_EXCEEDED");
        }
        if (activeReservations.size() + ledger.size() >= limits.maxCalls()) {
            throw new BudgetExceededException("CALL_BUDGET_EXCEEDED");
        }
        long requestedTokens = request.reservedTokens();
        requireWithin("AGENT_TOKEN_BUDGET_EXCEEDED", tokensForAgent(request.scope().agentId()),
                requestedTokens, limits.maxAgentTokens());
        requireWithin("TASK_TOKEN_BUDGET_EXCEEDED", tokensForTask(request.scope().taskId()),
                requestedTokens, limits.maxTaskTokens());
        requireWithin("INCIDENT_TOKEN_BUDGET_EXCEEDED", allTokens(), requestedTokens,
                limits.maxIncidentTokens());
        if (limits.maxCostMicros() != null && request.cost().costMicros() != null) {
            requireWithin("COST_BUDGET_EXCEEDED", allCost(), request.cost().costMicros(), limits.maxCostMicros());
        }
        activeReservations.put(request.invocationId(), request);
        return request;
    }

    public synchronized LedgerEntry reconcile(
            UUID invocationId,
            ProviderUsage providerUsage,
            long cachedTokens,
            AttemptOutcome outcome,
            Instant recordedAt) {
        ReservationRequest reservation = activeReservations.remove(Objects.requireNonNull(invocationId, "invocationId"));
        if (reservation == null) {
            throw new IllegalStateException(ledger.containsKey(invocationId)
                    ? "INVOCATION_ALREADY_RECONCILED" : "INVOCATION_NOT_RESERVED");
        }
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(recordedAt, "recordedAt");
        LedgerEntry entry;
        if (providerUsage != null) {
            Long cost = providerUsage.costMicros() != null
                    ? providerUsage.costMicros() : reservation.cost().costMicros();
            entry = new LedgerEntry(invocationId, reservation.scope(), providerUsage.inputTokens(),
                    providerUsage.outputTokens(), cachedTokens, cost,
                    providerUsage.costMicros() == null && cost != null,
                    reservation.cost().priceTableVersion(), UsageSource.PROVIDER, null, outcome, recordedAt);
        } else {
            ConservativeUsage estimate = estimator.estimate(reservation, outcome);
            if (estimate == null) {
                throw new IllegalStateException("USAGE_ESTIMATOR_RETURNED_NULL");
            }
            entry = new LedgerEntry(invocationId, reservation.scope(), estimate.inputTokens(),
                    estimate.outputTokens(), cachedTokens, reservation.cost().costMicros(),
                    reservation.cost().costMicros() != null, reservation.cost().priceTableVersion(),
                    UsageSource.CONSERVATIVE_ESTIMATE, estimate.estimatorVersion(), outcome, recordedAt);
        }
        ledger.put(invocationId, entry);
        return entry;
    }

    public synchronized BudgetSnapshot snapshot() {
        long consumed = ledger.values().stream().mapToLong(LedgerEntry::totalTokens).sum();
        long reserved = activeReservations.values().stream().mapToLong(ReservationRequest::reservedTokens).sum();
        Long cost = limits.maxCostMicros() == null && allCost() == 0 ? null : allCost();
        return new BudgetSnapshot(consumed, reserved, activeReservations.size() + ledger.size(), cost,
                new ArrayList<>(ledger.values()));
    }

    private long tokensForAgent(String agentId) {
        return activeReservations.values().stream()
                .filter(value -> value.scope().agentId().equals(agentId))
                .mapToLong(ReservationRequest::reservedTokens).sum()
                + ledger.values().stream().filter(value -> value.scope().agentId().equals(agentId))
                .mapToLong(LedgerEntry::totalTokens).sum();
    }

    private long tokensForTask(String taskId) {
        return activeReservations.values().stream()
                .filter(value -> value.scope().taskId().equals(taskId))
                .mapToLong(ReservationRequest::reservedTokens).sum()
                + ledger.values().stream().filter(value -> value.scope().taskId().equals(taskId))
                .mapToLong(LedgerEntry::totalTokens).sum();
    }

    private long allTokens() {
        return activeReservations.values().stream().mapToLong(ReservationRequest::reservedTokens).sum()
                + ledger.values().stream().mapToLong(LedgerEntry::totalTokens).sum();
    }

    private long allCost() {
        return activeReservations.values().stream()
                .map(ReservationRequest::cost).map(CostReservation::costMicros)
                .filter(Objects::nonNull).mapToLong(Long::longValue).sum()
                + ledger.values().stream().map(LedgerEntry::costMicros)
                .filter(Objects::nonNull).mapToLong(Long::longValue).sum();
    }

    private static void requireWithin(String code, long current, long requested, long limit) {
        if (requested > limit - current) {
            throw new BudgetExceededException(code);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static void requirePositive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }
}
