package io.github.opspilot.core.context;

import io.github.opspilot.core.application.context.ContextCompactor;
import io.github.opspilot.core.application.context.ContextCompactor.ActionKind;
import io.github.opspilot.core.application.context.ContextCompactor.ContextItem;
import io.github.opspilot.core.application.context.ContextCompactor.ItemKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextCompactorTest {
    @Test
    void followsFrozenOrderAndKeepsEarlyCriticalSemantics() {
        List<ContextItem> input = List.of(
                item("early-decision", ItemKind.DECISION, 30, null),
                item("tool-first", ItemKind.TOOL_OUTPUT, 10, "query-a"),
                item("tool-duplicate", ItemKind.TOOL_OUTPUT, 10, "query-a"),
                item("completed", ItemKind.COMPLETED_STEP_DETAIL, 20, null),
                item("candidate", ItemKind.LOW_RELEVANCE_CANDIDATE, 30, null),
                item("conclusion", ItemKind.CONCLUSION, 30, null),
                item("unresolved", ItemKind.UNRESOLVED_QUESTION, 30, null),
                item("constraint", ItemKind.CONSTRAINT, 30, null),
                item("artifact", ItemKind.ARTIFACT_REFERENCE, 30, null));

        var result = new ContextCompactor().compact(input, 60);

        assertEquals(List.of(
                ActionKind.REMOVE_DUPLICATE_TOOL_OUTPUT,
                ActionKind.REMOVE_COMPLETED_STEP_DETAIL,
                ActionKind.REMOVE_LOW_RELEVANCE_CANDIDATE),
                result.actions().stream().map(action -> action.kind()).toList());
        assertEquals(List.of("tool-duplicate", "completed", "candidate"),
                result.actions().stream().map(action -> action.itemId()).toList());
        assertEquals(60, result.freedTokens());
        assertEquals(0, result.shortfallTokens());
        assertTrue(result.retainedItems().stream().anyMatch(item -> item.itemId().equals("early-decision")));
        assertTrue(result.retainedItems().stream().anyMatch(item -> item.itemId().equals("artifact")));
    }

    @Test
    void reportsShortfallInsteadOfTruncatingProtectedOrOrdinaryItems() {
        List<ContextItem> input = List.of(
                item("decision", ItemKind.DECISION, 20, null),
                item("ordinary", ItemKind.OTHER, 20, null),
                item("artifact", ItemKind.ARTIFACT_REFERENCE, 20, null));

        var result = new ContextCompactor().compact(input, 25);

        assertEquals(input, result.retainedItems());
        assertTrue(result.actions().isEmpty());
        assertEquals(25, result.shortfallTokens());
    }

    @Test
    void keepsTheFirstToolOutputAndOnlyCompactsRepetitions() {
        List<ContextItem> input = List.of(
                item("first", ItemKind.TOOL_OUTPUT, 12, "same"),
                item("second", ItemKind.TOOL_OUTPUT, 12, "same"),
                item("unique", ItemKind.TOOL_OUTPUT, 12, "unique"));

        var result = new ContextCompactor().compact(input, 12);

        assertEquals(List.of("first", "unique"),
                result.retainedItems().stream().map(ContextItem::itemId).toList());
    }

    private static ContextItem item(String id, ItemKind kind, int tokens, String deduplicationKey) {
        return new ContextItem(id, kind, id + " content", tokens, deduplicationKey);
    }
}
