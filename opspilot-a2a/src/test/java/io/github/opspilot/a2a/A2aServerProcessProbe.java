package io.github.opspilot.a2a;

import io.github.opspilot.a2a.server.Phase0A2aServer;
import io.github.opspilot.a2a.server.PostgresA2aTaskStore;

/** Child-JVM server used to prove the A2A client cannot call an in-process server object. */
public final class A2aServerProcessProbe {

    private A2aServerProcessProbe() {
    }

    public static void main(String[] args) throws Exception {
        try (PostgresA2aTaskStore store = new PostgresA2aTaskStore(
                args[0], args[1], args[2]);
             Phase0A2aServer server = new Phase0A2aServer(
                     store, Integer.parseInt(args[3]), System.out::println)) {
            server.start();
            System.out.println("READY processId=" + ProcessHandle.current().pid()
                    + " port=" + server.port());
            while (System.in.read() != -1) {
                // Parent closes stdin to request a clean shutdown.
            }
        }
    }
}
