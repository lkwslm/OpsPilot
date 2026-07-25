package io.github.opspilot.faultlab;

import java.util.Objects;

public final class InventoryEnvironmentController implements EnvironmentControlPort {
    private final DockerCliContainerControlAdapter containers;
    private final ToxiproxyControlAdapter proxy;

    public InventoryEnvironmentController(
            DockerCliContainerControlAdapter containers, ToxiproxyControlAdapter proxy) {
        this.containers = Objects.requireNonNull(containers, "containers");
        this.proxy = Objects.requireNonNull(proxy, "proxy");
    }

    @Override public void stopInventory() { containers.stopInventory(); }
    @Override public void startInventory() { containers.startInventory(); }
    @Override public void addInventoryLatency(long latencyMillis, long jitterMillis) {
        proxy.addLatency(latencyMillis, jitterMillis);
    }
    @Override public void resetInventoryProxy() { proxy.reset(); }

    public void withLatency(long latencyMillis, long jitterMillis, Runnable verification) {
        addInventoryLatency(latencyMillis, jitterMillis);
        try {
            verification.run();
        } finally {
            resetInventoryProxy();
        }
    }

    public void withInventoryStopped(Runnable verification) {
        stopInventory();
        try {
            verification.run();
        } finally {
            startInventory();
        }
    }
}
