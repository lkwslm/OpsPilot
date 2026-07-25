package io.github.opspilot.faultlab;

public interface EnvironmentControlPort {
    void stopInventory();
    void startInventory();
    void addInventoryLatency(long latencyMillis, long jitterMillis);
    void resetInventoryProxy();
}
