package io.github.opspilot.core.application.profile;

import io.github.opspilot.core.application.profile.AgentProfile.Permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Computes the fail-closed intersection of all five authorization layers. */
public final class EffectivePermissionCalculator {
    private EffectivePermissionCalculator() { }

    public record PermissionLayer(
            String name, Set<String> toolIds, Set<String> skillIds,
            Set<String> resourceScopes, Permission maxPermission, boolean active) {
        public PermissionLayer {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
            toolIds = Set.copyOf(Objects.requireNonNull(toolIds, "toolIds"));
            skillIds = Set.copyOf(Objects.requireNonNull(skillIds, "skillIds"));
            resourceScopes = Set.copyOf(Objects.requireNonNull(resourceScopes, "resourceScopes"));
            Objects.requireNonNull(maxPermission, "maxPermission");
        }
    }

    public record ActionRequest(
            String toolId, String skillId, String resourceScope,
            Permission permission, boolean highRisk, boolean codeMutation) {
        public ActionRequest {
            Objects.requireNonNull(permission, "permission");
        }
    }

    public record LayerDecision(String layer, boolean allowed, String reason) { }

    public record PermissionDecision(boolean allowed, String code, List<LayerDecision> layers) {
        public PermissionDecision { layers = List.copyOf(layers); }
    }

    public static PermissionDecision decide(ActionRequest request, List<PermissionLayer> layers) {
        Objects.requireNonNull(request, "request");
        if (layers == null || layers.size() != 5) {
            return new PermissionDecision(false, "AUTHORIZATION_LAYER_MISSING", List.of());
        }
        List<LayerDecision> decisions = new ArrayList<>();
        if (request.highRisk() || request.codeMutation()) {
            layers.forEach(layer -> decisions.add(new LayerDecision(layer.name(), false, "PLATFORM_BASELINE_DENIED")));
            return new PermissionDecision(false, "PLATFORM_BASELINE_DENIED", decisions);
        }
        boolean allowed = true;
        for (PermissionLayer layer : layers) {
            boolean permits = layer.active()
                    && layer.maxPermission().ordinal() >= request.permission().ordinal()
                    && (request.toolId() == null || layer.toolIds().contains(request.toolId()))
                    && (request.skillId() == null || layer.skillIds().contains(request.skillId()))
                    && (request.resourceScope() == null || layer.resourceScopes().contains(request.resourceScope()));
            decisions.add(new LayerDecision(layer.name(), permits, permits ? "ALLOWED" : "DENIED"));
            allowed &= permits;
        }
        return new PermissionDecision(allowed, allowed ? "ALLOWED" : "PERMISSION_INTERSECTION_DENIED", decisions);
    }
}
