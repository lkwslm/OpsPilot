package io.github.opspilot.server;

/** Dedicated 8081-8085 professional Agent image entry point. */
public final class ProfessionalAgentProcess {
    private ProfessionalAgentProcess() {
    }

    public static void main(String[] args) throws Exception {
        requireProfile(System.getenv("AGENT_ID"));
        Phase0Process.serve(System.getenv());
    }

    static void requireProfile(String agentId) {
        if (agentId == null || "supervisor".equals(agentId)) {
            throw new IllegalStateException("PROFESSIONAL_ENTRYPOINT_PROFILE_FORBIDDEN");
        }
    }
}
