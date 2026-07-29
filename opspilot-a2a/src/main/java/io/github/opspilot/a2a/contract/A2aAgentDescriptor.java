package io.github.opspilot.a2a.contract;

import java.util.List;

/** Project-owned card view so official SDK objects remain inside the A2A module. */
public record A2aAgentDescriptor(
        String agentId,
        String name,
        String version,
        String skillId,
        String endpoint,
        String protocolVersion,
        List<String> inputMediaTypes,
        List<String> outputMediaTypes,
        boolean streaming,
        List<String> requiredExtensions) {

    public A2aAgentDescriptor {
        inputMediaTypes = List.copyOf(inputMediaTypes);
        outputMediaTypes = List.copyOf(outputMediaTypes);
        requiredExtensions = List.copyOf(requiredExtensions);
    }
}
