package io.github.opspilot.adapters.persistence.postgres;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Committed SSE facts replayed strictly within one Run. */
public final class SseEventRepository {
    private final DataSource dataSource;

    public SseEventRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public void append(Event event) {
        String sql = """
                INSERT INTO opspilot.sse_event
                    (event_id, run_id, sequence_no, event_type, payload_json, committed_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?)
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, event.eventId());
            statement.setObject(2, event.runId());
            statement.setLong(3, event.sequence());
            statement.setString(4, event.eventType());
            statement.setString(5, event.payloadJson());
            statement.setTimestamp(6, Timestamp.from(event.committedAt()));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("SSE_APPEND_FAILED", exception);
        }
    }

    public List<Event> replayAfter(UUID runId, UUID lastEventId, int limit) {
        if (limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException("limit must be between 1 and 10000");
        }
        String sql = """
                SELECT event_id, run_id, sequence_no, event_type, payload_json::text, committed_at
                FROM opspilot.sse_event
                WHERE run_id = ?
                  AND sequence_no > COALESCE((
                      SELECT sequence_no FROM opspilot.sse_event
                      WHERE run_id = ? AND event_id = ?
                  ), CASE WHEN ? IS NULL THEN -1 ELSE 9223372036854775807 END)
                ORDER BY sequence_no
                LIMIT ?
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, runId);
            statement.setObject(2, runId);
            statement.setObject(3, lastEventId);
            statement.setObject(4, lastEventId);
            statement.setInt(5, limit);
            List<Event> events = new ArrayList<>();
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    events.add(new Event(
                            result.getObject(1, UUID.class), result.getObject(2, UUID.class),
                            result.getLong(3), result.getString(4), result.getString(5),
                            result.getTimestamp(6).toInstant()));
                }
            }
            return List.copyOf(events);
        } catch (SQLException exception) {
            throw new IllegalStateException("SSE_REPLAY_FAILED", exception);
        }
    }

    public record Event(
            UUID eventId, UUID runId, long sequence, String eventType,
            String payloadJson, Instant committedAt) {
        public Event {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(eventType, "eventType");
            Objects.requireNonNull(payloadJson, "payloadJson");
            Objects.requireNonNull(committedAt, "committedAt");
            if (sequence < 0 || !payloadJson.contains("\"schemaVersion\"")) {
                throw new IllegalArgumentException("sequence and schemaVersion are required");
            }
        }
    }
}
