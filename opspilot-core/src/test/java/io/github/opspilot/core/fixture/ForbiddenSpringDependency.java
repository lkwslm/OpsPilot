package io.github.opspilot.core.fixture;

import org.springframework.context.ApplicationContext;

/** Test-only negative control; never included in production classes. */
public final class ForbiddenSpringDependency {
    private final ApplicationContext applicationContext;

    public ForbiddenSpringDependency(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public ApplicationContext applicationContext() {
        return applicationContext;
    }
}
