package io.github.opspilot.faultlab;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class EnvironmentControlTest {
    @Test
    void containerAdapterOnlyStopsAndStartsTheOriginalComposeServiceIdentity() {
        List<List<String>> commands = new ArrayList<>();
        DockerCliContainerControlAdapter adapter = new DockerCliContainerControlAdapter(
                "opspilot-phase4", (command, timeout) -> { commands.add(command); return 0; });
        adapter.stopInventory();
        adapter.startInventory();

        assertEquals("stop", commands.get(0).get(6));
        assertEquals("start", commands.get(1).get(6));
        assertEquals("inventory-service", commands.get(0).get(7));
        assertTrue(commands.stream().flatMap(List::stream).noneMatch(value -> value.equals("rm") || value.equals("kill")));
    }

    @Test
    void toxiproxyResetRunsInFinallyAndIsIdempotent() throws Exception {
        AtomicInteger resets = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/proxies/inventory-downstream/toxics", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/proxies/inventory-downstream/toxics/inventory-latency", exchange -> {
            resets.incrementAndGet();
            exchange.sendResponseHeaders(resets.get() == 1 ? 204 : 404, -1);
            exchange.close();
        });
        server.start();
        try {
            ToxiproxyControlAdapter proxy = new ToxiproxyControlAdapter(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            DockerCliContainerControlAdapter containers = new DockerCliContainerControlAdapter(
                    "opspilot-phase4", (command, timeout) -> 0);
            InventoryEnvironmentController controller = new InventoryEnvironmentController(containers, proxy);
            assertThrows(IllegalStateException.class,
                    () -> controller.withLatency(3000, 100, () -> { throw new IllegalStateException("abort"); }));
            controller.resetInventoryProxy();
            assertEquals(2, resets.get());
        } finally {
            server.stop(0);
        }
    }
}
