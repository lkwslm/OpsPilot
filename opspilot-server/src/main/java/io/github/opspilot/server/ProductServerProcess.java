package io.github.opspilot.server;

/** Dedicated 8080 product/Supervisor image entry point. */
public final class ProductServerProcess {
    private ProductServerProcess() {
    }

    public static void main(String[] args) throws Exception {
        requireProfile(System.getenv("AGENT_ID"));
        Phase0Process.serve(System.getenv());
    }

    static void requireProfile(String agentId) {
        if (!"supervisor".equals(agentId)) {
            throw new IllegalStateException("PRODUCT_ENTRYPOINT_PROFILE_FORBIDDEN");
        }
    }
}
