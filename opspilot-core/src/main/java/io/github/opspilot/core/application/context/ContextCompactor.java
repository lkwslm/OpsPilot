package io.github.opspilot.core.application.context;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Deterministic context compaction that never falls back to chronological truncation. */
public final class ContextCompactor {
    public enum ItemKind {
        TOOL_OUTPUT,
        COMPLETED_STEP_DETAIL,
        LOW_RELEVANCE_CANDIDATE,
        DECISION,
        CONCLUSION,
        UNRESOLVED_QUESTION,
        CONSTRAINT,
        ARTIFACT_REFERENCE,
        OTHER
    }

    public enum ActionKind {
        REMOVE_DUPLICATE_TOOL_OUTPUT,
        REMOVE_COMPLETED_STEP_DETAIL,
        REMOVE_LOW_RELEVANCE_CANDIDATE
    }

    public record ContextItem(
            String itemId,
            ItemKind kind,
            String content,
            int estimatedTokens,
            String deduplicationKey) {
        public ContextItem {
            itemId = requireText(itemId, "itemId");
            Objects.requireNonNull(kind, "kind");
            content = Objects.requireNonNull(content, "content");
            if (estimatedTokens <= 0) {
                throw new IllegalArgumentException("estimatedTokens must be positive");
            }
            if (kind == ItemKind.TOOL_OUTPUT) {
                deduplicationKey = requireText(deduplicationKey, "deduplicationKey");
            }
        }
    }

    public record CompactionAction(ActionKind kind, String itemId, int tokensFreed) {
    }

    public record CompactionResult(
            List<ContextItem> retainedItems,
            List<CompactionAction> actions,
            int requestedTokens,
            int freedTokens,
            int shortfallTokens) {
        public CompactionResult {
            retainedItems = List.copyOf(retainedItems);
            actions = List.copyOf(actions);
        }
    }

    public CompactionResult compact(List<ContextItem> input, int tokensToFree) {
        List<ContextItem> items = List.copyOf(Objects.requireNonNull(input, "input"));
        if (tokensToFree < 0) {
            throw new IllegalArgumentException("tokensToFree must not be negative");
        }
        ensureUniqueIds(items);
        LinkedHashSet<String> removedIds = new LinkedHashSet<>();
        List<CompactionAction> actions = new ArrayList<>();
        int freed = removeDuplicateToolOutputs(items, tokensToFree, removedIds, actions);
        freed += removeByKind(items, ItemKind.COMPLETED_STEP_DETAIL,
                ActionKind.REMOVE_COMPLETED_STEP_DETAIL, tokensToFree - freed, removedIds, actions);
        freed += removeByKind(items, ItemKind.LOW_RELEVANCE_CANDIDATE,
                ActionKind.REMOVE_LOW_RELEVANCE_CANDIDATE, tokensToFree - freed, removedIds, actions);
        List<ContextItem> retained = items.stream()
                .filter(item -> !removedIds.contains(item.itemId()))
                .toList();
        return new CompactionResult(retained, actions, tokensToFree, freed,
                Math.max(0, tokensToFree - freed));
    }

    private static int removeDuplicateToolOutputs(
            List<ContextItem> items,
            int tokensToFree,
            Set<String> removedIds,
            List<CompactionAction> actions) {
        if (tokensToFree <= 0) {
            return 0;
        }
        Set<String> seen = new HashSet<>();
        int freed = 0;
        for (ContextItem item : items) {
            if (item.kind() != ItemKind.TOOL_OUTPUT || seen.add(item.deduplicationKey())) {
                continue;
            }
            removedIds.add(item.itemId());
            actions.add(new CompactionAction(
                    ActionKind.REMOVE_DUPLICATE_TOOL_OUTPUT, item.itemId(), item.estimatedTokens()));
            freed += item.estimatedTokens();
            if (freed >= tokensToFree) {
                break;
            }
        }
        return freed;
    }

    private static int removeByKind(
            List<ContextItem> items,
            ItemKind itemKind,
            ActionKind actionKind,
            int tokensToFree,
            Set<String> removedIds,
            List<CompactionAction> actions) {
        if (tokensToFree <= 0) {
            return 0;
        }
        int freed = 0;
        for (ContextItem item : items) {
            if (item.kind() != itemKind || removedIds.contains(item.itemId())) {
                continue;
            }
            removedIds.add(item.itemId());
            actions.add(new CompactionAction(actionKind, item.itemId(), item.estimatedTokens()));
            freed += item.estimatedTokens();
            if (freed >= tokensToFree) {
                break;
            }
        }
        return freed;
    }

    private static void ensureUniqueIds(List<ContextItem> items) {
        Set<String> ids = new HashSet<>();
        for (ContextItem item : items) {
            if (!ids.add(item.itemId())) {
                throw new IllegalArgumentException("duplicate context item id: " + item.itemId());
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
