package io.github.opspilot.core.fixture;

public final class ForbiddenDependencyFixtures {
    org.springframework.context.ApplicationContext spring;
    io.agentscope.AgentScopeApi agentScope;
    org.a2aproject.sdk.A2aSdkApi a2a;
    jakarta.persistence.JpaApi jpa;
    io.prometheus.PrometheusApi prometheus;
    io.jaegertracing.JaegerApi jaeger;
    ai.infinity.InfinityApi infinity;
    com.openai.VendorClient vendor;
}
