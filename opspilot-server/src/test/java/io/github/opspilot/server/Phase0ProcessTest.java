package io.github.opspilot.server;

import com.sun.net.httpserver.HttpServer;
import io.github.opspilot.adapters.persistence.postgres.PostgresReadinessCheck;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.net.InetSocketAddress;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Phase0ProcessTest {
    @Test
    void temporaryDependencyFailureKeepsLivenessIndependentAndRecoversOnNextProbe() throws Exception {
        int port;
        try (var socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        String url = "http://127.0.0.1:" + port + "/card";
        assertFalse(Phase0Process.readiness(new String[]{url}).ready());

        HttpServer dependency = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        dependency.createContext("/card", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        dependency.start();
        try {
            assertTrue(waitUntilReady(url));
        } finally {
            dependency.stop(0);
        }
    }

    @Test
    void databaseMismatchKeepsReadinessDownBeforeDependencyTraffic() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setUrl("jdbc:postgresql://127.0.0.1:1/unreachable?connectTimeout=1");
        dataSource.setUser("none");
        dataSource.setPassword("none");
        PostgresReadinessCheck database = new PostgresReadinessCheck(dataSource, "7", "0.8.4");
        var readiness = Phase0Process.readiness(new String[0], database);
        assertFalse(readiness.ready());
        assertTrue(readiness.reason().contains("POSTGRES_READINESS_FAILED"));
    }

    private static boolean waitUntilReady(String url) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (Phase0Process.readiness(new String[]{url}).ready()) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
}
