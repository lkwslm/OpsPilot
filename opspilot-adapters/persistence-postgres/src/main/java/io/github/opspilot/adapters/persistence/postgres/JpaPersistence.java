package io.github.opspilot.adapters.persistence.postgres;

import io.github.opspilot.adapters.persistence.postgres.entity.AgentStateEntity;
import io.github.opspilot.adapters.persistence.postgres.entity.ArtifactEntity;
import io.github.opspilot.adapters.persistence.postgres.entity.HypothesisEntity;
import io.github.opspilot.adapters.persistence.postgres.entity.IncidentEntity;
import io.github.opspilot.adapters.persistence.postgres.entity.IncidentRunEntity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceUnitTransactionType;

import javax.sql.DataSource;
import java.util.Objects;

/** Standard JPA bootstrap over the application DataSource; Flyway remains the only DDL owner. */
public final class JpaPersistence {
    private JpaPersistence() {
    }

    public static EntityManagerFactory createEntityManagerFactory(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        return new PersistenceConfiguration("opspilot-postgres")
                .provider("org.hibernate.jpa.HibernatePersistenceProvider")
                .transactionType(PersistenceUnitTransactionType.RESOURCE_LOCAL)
                .managedClass(IncidentEntity.class)
                .managedClass(IncidentRunEntity.class)
                .managedClass(AgentStateEntity.class)
                .managedClass(ArtifactEntity.class)
                .managedClass(HypothesisEntity.class)
                .property(PersistenceConfiguration.JDBC_DATASOURCE, dataSource)
                .property("hibernate.connection.datasource", dataSource)
                .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "none")
                .property(PersistenceConfiguration.SCHEMAGEN_SCRIPTS_ACTION, "none")
                .property("hibernate.hbm2ddl.auto", "validate")
                .property("hibernate.archive.autodetection", "none")
                .property("hibernate.jdbc.time_zone", "UTC")
                .property("hibernate.type.json_format_mapper", "jackson")
                .createEntityManagerFactory();
    }
}
