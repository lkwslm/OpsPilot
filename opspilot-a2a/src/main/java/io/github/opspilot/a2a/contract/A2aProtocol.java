package io.github.opspilot.a2a.contract;

/** Locked A2A protocol constants shared by the HTTP client and server. */
public final class A2aProtocol {

    public static final String RELEASE = "v1.0.1";
    public static final String VERSION = "1.0";
    public static final String MEDIA_TYPE = "application/a2a+json";
    public static final String VERSION_HEADER = "A2A-Version";
    public static final String SERVICE_ID_HEADER = "X-OpsPilot-Service-Id";
    public static final String CORRELATION_EXTENSION =
            "https://opspilot.local/extensions/correlation/v1";

    private A2aProtocol() {
    }
}
