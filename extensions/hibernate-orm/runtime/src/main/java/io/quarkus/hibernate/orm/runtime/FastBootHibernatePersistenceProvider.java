package io.quarkus.hibernate.orm.runtime;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.spi.PersistenceProvider;
import jakarta.persistence.spi.PersistenceUnitInfo;

import org.hibernate.boot.registry.StandardServiceInitiator;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.internal.StandardServiceRegistryImpl;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.jpa.HibernateHints;
import org.hibernate.jpa.boot.spi.EntityManagerFactoryBuilder;
import org.hibernate.jpa.boot.spi.PersistenceUnitDescriptor;
import org.hibernate.service.Service;
import org.hibernate.service.internal.ProvidedService;
import org.jboss.logging.Logger;

import io.quarkus.agroal.runtime.AgroalDataSourceUtil;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ClientProxy;
import io.quarkus.hibernate.orm.runtime.RuntimeSettings.Builder;
import io.quarkus.hibernate.orm.runtime.boot.FastBootEntityManagerFactoryBuilder;
import io.quarkus.hibernate.orm.runtime.boot.QuarkusPersistenceUnitDescriptor;
import io.quarkus.hibernate.orm.runtime.boot.registry.PreconfiguredServiceRegistryBuilder;
import io.quarkus.hibernate.orm.runtime.config.DatabaseOrmCompatibilityVersion;
import io.quarkus.hibernate.orm.runtime.integration.HibernateOrmIntegrationRuntimeDescriptor;
import io.quarkus.hibernate.orm.runtime.integration.HibernateOrmIntegrationRuntimeInitListener;
import io.quarkus.hibernate.orm.runtime.migration.MultiTenancyStrategy;
import io.quarkus.hibernate.orm.runtime.recording.PrevalidatedQuarkusMetadata;
import io.quarkus.hibernate.orm.runtime.recording.RecordedState;

/**
 * This can not inherit from HibernatePersistenceProvider as that would force
 * the native-image tool to include all code which could be triggered from using
 * that: we need to be able to fully exclude HibernatePersistenceProvider from
 * the native image.
 */
public final class FastBootHibernatePersistenceProvider implements PersistenceProvider {

    private static final Logger log = Logger.getLogger(FastBootHibernatePersistenceProvider.class);

    private final ProviderUtil providerUtil = new ProviderUtil();

    private final HibernateOrmRuntimeConfig hibernateOrmRuntimeConfig;
    private final Map<String, List<HibernateOrmIntegrationRuntimeDescriptor>> integrationRuntimeDescriptors;

    public FastBootHibernatePersistenceProvider(HibernateOrmRuntimeConfig hibernateOrmRuntimeConfig,
            Map<String, List<HibernateOrmIntegrationRuntimeDescriptor>> integrationRuntimeDescriptors) {
        this.hibernateOrmRuntimeConfig = hibernateOrmRuntimeConfig;
        this.integrationRuntimeDescriptors = integrationRuntimeDescriptors;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public EntityManagerFactory createEntityManagerFactory(String persistenceUnitName, Map properties) {
        log.tracef("Starting createEntityManagerFactory for persistenceUnitName %s", persistenceUnitName);

        boolean isReactive = (boolean) properties.get(JPAConfig.IS_REACTIVE_KEY);
        if (isReactive) {
            log.debug(
                    "FastBootHibernatePersistenceProvider called on a reactive initialization thread, skipping this provider");
            return null;
        }

        // There's a check to not have any init properties later
        properties = null;

        final EntityManagerFactoryBuilder builder = getEntityManagerFactoryBuilderOrNull(persistenceUnitName,
                properties);
        if (builder == null) {
            log.trace("Could not obtain matching EntityManagerFactoryBuilder, returning null");
            return null;
        } else {
            return builder.build();
        }
    }

    @SuppressWarnings("rawtypes")
    @Override
    public EntityManagerFactory createContainerEntityManagerFactory(PersistenceUnitInfo info, Map properties) {
        log.tracef("Starting createContainerEntityManagerFactory : %s", info.getPersistenceUnitName());

        return getEntityManagerFactoryBuilder(info, properties).build();
    }

    @Override
    public EntityManagerFactory createEntityManagerFactory(PersistenceConfiguration configuration) {
        throw new PersistenceException(
                "This PersistenceProvider does not support createEntityManagerFactory(PersistenceConfiguration). "
                        + " Quarkus is responsible for creating the entity manager factory, so inject your entity manager"
                        + " factory through CDI instead: `@Inject EntityManagerFactory emf`.");
    }

    @SuppressWarnings("rawtypes")
    @Override
    public void generateSchema(PersistenceUnitInfo info, Map map) {
        log.tracef("Starting generateSchema : PUI.name=%s", info.getPersistenceUnitName());

        final EntityManagerFactoryBuilder builder = getEntityManagerFactoryBuilder(info, map);
        builder.generateSchema();
    }

    @SuppressWarnings("rawtypes")
    @Override
    public boolean generateSchema(String persistenceUnitName, Map map) {
        log.tracef("Starting generateSchema for persistenceUnitName %s", persistenceUnitName);

        final EntityManagerFactoryBuilder builder = getEntityManagerFactoryBuilderOrNull(persistenceUnitName, map);
        if (builder == null) {
            log.trace("Could not obtain matching EntityManagerFactoryBuilder, returning false");
            return false;
        }
        builder.generateSchema();
        return true;
    }

    @Override
    public ProviderUtil getProviderUtil() {
        return providerUtil;
    }

    @SuppressWarnings("rawtypes")
    public EntityManagerFactoryBuilder getEntityManagerFactoryBuilder(PersistenceUnitInfo info, Map integration) {
        return getEntityManagerFactoryBuilder(info.getPersistenceUnitName(), integration);
    }

    public EntityManagerFactoryBuilder getEntityManagerFactoryBuilder(String persistenceUnitName, Map integration) {
        return getEntityManagerFactoryBuilderOrNull(persistenceUnitName, integration);
    }

    /**
     * Copied and modified from
     * HibernatePersistenceProvider#getEntityManagerFactoryBuilderOrNull(String,
     * Map, ClassLoader, ClassLoaderService) Notable changes: - ignore the
     * ClassLoaderService parameter to inject our own custom implementation instead
     * - verify the Map properties are not set (or fail as we can't support runtime
     * overrides) - don't try looking for ParsedPersistenceXmlDescriptor resources
     * to parse, just take the pre-parsed ones from the static final field - class
     * annotations metadata is also injected
     */
    @SuppressWarnings("rawtypes")
    private EntityManagerFactoryBuilder getEntityManagerFactoryBuilderOrNull(String persistenceUnitName,
            Map properties) {
        log.tracef("Attempting to obtain correct EntityManagerFactoryBuilder for persistenceUnitName : %s",
                persistenceUnitName);

        verifyProperties(properties);

        // These are pre-parsed during image generation:
        // Avoid processing the reactive descriptors
        final List<QuarkusPersistenceUnitDescriptor> units = PersistenceUnitsHolder
                .getPersistenceUnitDescriptors()
                .stream()
                .filter(d -> !d.isReactive())
                .toList();

        log.debugf("Located %s persistence units; checking each", units.size());

        if (persistenceUnitName == null && units.size() > 1) {
            // no persistence-unit name to look for was given and we found multiple
            // persistence-units
            throw new PersistenceException("No name provided and multiple persistence units found");
        }

        for (QuarkusPersistenceUnitDescriptor persistenceUnit : units) {
            log.debugf(
                    "Checking persistence-unit [name=%s, explicit-provider=%s] against incoming persistence unit name [%s]",
                    persistenceUnit.getName(), persistenceUnit.getProviderClassName(), persistenceUnitName);

            final boolean matches = persistenceUnitName == null
                    || persistenceUnit.getName().equals(persistenceUnitName);
            if (!matches) {
                log.debugf("Excluding from consideration '%s' due to name mismatch", persistenceUnit.getName());
                continue;
            }

            // See if we (Hibernate) are the persistence provider
            if (!isProvider(persistenceUnit)) {
                log.debug("Excluding from consideration due to provider mismatch");
                continue;
            }

            RecordedState recordedState = PersistenceUnitsHolder.popRecordedState(persistenceUnitName, false);

            if (recordedState.isReactive()) {
                throw new IllegalStateException(
                        "Attempting to boot a blocking Hibernate ORM instance on a reactive RecordedState");
            }
            final PrevalidatedQuarkusMetadata metadata = recordedState.getMetadata();
            var puConfig = hibernateOrmRuntimeConfig.persistenceUnits().get(persistenceUnit.getName());
            if (puConfig.active().isPresent() && !puConfig.active().get()) {
                throw new IllegalStateException(
                        "Attempting to boot a deactivated Hibernate ORM persistence unit");
            }
            RuntimeSettings runtimeSettings = buildRuntimeSettings(persistenceUnitName, recordedState, puConfig);

            StandardServiceRegistry standardServiceRegistry = rewireMetadataAndExtractServiceRegistry(persistenceUnitName,
                    recordedState, puConfig, runtimeSettings);

            final Object cdiBeanManager = Arc.container().beanManager();
            final Object validatorFactory = Arc.container().instance("quarkus-hibernate-validator-factory").get();

            return new FastBootEntityManagerFactoryBuilder(
                    persistenceUnit,
                    metadata /* Uses the StandardServiceRegistry references by this! */,
                    standardServiceRegistry /* Mostly ignored! (yet needs to match) */,
                    runtimeSettings,
                    validatorFactory, cdiBeanManager, recordedState.getMultiTenancyStrategy(),
                    true,
                    recordedState.getBuildTimeSettings().getSource().getBuiltinFormatMapperBehaviour(),
                    recordedState.getBuildTimeSettings().getSource().getJsonFormatterCustomizationCheck());
        }

        log.debug("Found no matching persistence units");
        return null;
    }

    private RuntimeSettings buildRuntimeSettings(String persistenceUnitName, RecordedState recordedState,
            HibernateOrmRuntimeConfigPersistenceUnit persistenceUnitConfig) {
        final BuildTimeSettings buildTimeSettings = recordedState.getBuildTimeSettings();
        final IntegrationSettings integrationSettings = recordedState.getIntegrationSettings();
        Builder runtimeSettingsBuilder = new Builder(buildTimeSettings, integrationSettings);
        unzipZipFilesAndReplaceZipsInImportFiles(runtimeSettingsBuilder);

        Optional<String> dataSourceName = recordedState.getBuildTimeSettings().getSource().getDataSource();
        if (dataSourceName.isPresent()) {
            // Inject the datasource
            injectDataSource(persistenceUnitName, dataSourceName.get(), recordedState.getMultiTenancyStrategy(),
                    runtimeSettingsBuilder);
        }

        // Inject runtime configuration if the persistence unit was defined by Quarkus configuration
        if (!recordedState.isFromPersistenceXml()) {
            injectRuntimeConfiguration(persistenceUnitConfig, runtimeSettingsBuilder);
        }

        for (HibernateOrmIntegrationRuntimeDescriptor descriptor : integrationRuntimeDescriptors
                .getOrDefault(persistenceUnitName, Collections.emptyList())) {
            Optional<HibernateOrmIntegrationRuntimeInitListener> listenerOptional = descriptor.getInitListener();
            if (listenerOptional.isPresent()) {
                listenerOptional.get().contributeRuntimeProperties(runtimeSettingsBuilder::put);
            }
        }

        // Allow detection of driver/database capabilities on runtime init (was disabled during static init)
        runtimeSettingsBuilder.put(AvailableSettings.ALLOW_METADATA_ON_BOOT, "true");
        // Remove database version information, if any;
        // it was necessary during static init to force creation of a dialect,
        // but now the dialect is there, and we'll reuse it.
        // Keeping this information would prevent us from getting the actual information from the database on start.
        runtimeSettingsBuilder.put(AvailableSettings.JAKARTA_HBM2DDL_DB_VERSION, null);

        if (!persistenceUnitConfig.unsupportedProperties().isEmpty()) {
            log.warnf("Persistence-unit [%s] sets unsupported properties."
                    + " These properties may not work correctly, and even if they do,"
                    + " that may change when upgrading to a newer version of Quarkus (even just a micro/patch version)."
                    + " Consider using a supported configuration property before falling back to unsupported ones."
                    + " If there is no supported equivalent, make sure to file a feature request so that a supported configuration property can be added to Quarkus,"
                    + " and more importantly so that the configuration property is tested regularly."
                    + " Unsupported properties being set: %s",
                    persistenceUnitName,
                    persistenceUnitConfig.unsupportedProperties().keySet());
        }
        Set<String> overriddenProperties = new HashSet<>();
        for (Map.Entry<String, String> entry : persistenceUnitConfig.unsupportedProperties().entrySet()) {
            var key = entry.getKey();
            var value = runtimeSettingsBuilder.get(key);
            if (value != null && !(value instanceof String stringValue && stringValue.isBlank())) {
                overriddenProperties.add(key);
            }
            runtimeSettingsBuilder.put(entry.getKey(), entry.getValue());
        }
        if (!overriddenProperties.isEmpty()) {
            log.warnf("Persistence-unit [%s] sets unsupported properties that override Quarkus' own settings."
                    + " These properties may break assumptions in Quarkus code and cause malfunctions."
                    + " If this override is absolutely necessary, make sure to file a feature request or bug report so that a solution can be implemented in Quarkus."
                    + " Unsupported properties that override Quarkus' own settings: %s",
                    persistenceUnitName,
                    overriddenProperties);
        }

        var databaseOrmCompatibilityVersion = buildTimeSettings.getSource().getDatabaseOrmCompatibilityVersion();
        var databaseOrmCompatibilitySettings = buildTimeSettings.getDatabaseOrmCompatibilitySettings();
        if (databaseOrmCompatibilityVersion != DatabaseOrmCompatibilityVersion.LATEST) {
            log.warnf("Persistence-unit [%1$s]: enabling best-effort backwards compatibility with '%2$s=%3$s'."
                    + " Quarkus will attempt to change the behavior and expected schema of Hibernate ORM"
                    + " to match those of Hibernate ORM %3$s."
                    + " This is an inherently best-effort feature that cannot address all "
                    + " backwards-incompatible changes of Hibernate ORM 6."
                    + " It is also inherently unstable and may stop working in future versions of Quarkus."
                    + " Consider migrating your application to native Hibernate ORM 6 behavior;"
                    + " see https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.0:-Hibernate-ORM-5-to-6-migration for more information.",
                    persistenceUnitName,
                    HibernateOrmRuntimeConfig.puPropertyKey(persistenceUnitName, "database.orm-compatibility.version"),
                    databaseOrmCompatibilityVersion.externalRepresentation,
                    persistenceUnitConfig.unsupportedProperties().keySet());
        }
        for (Map.Entry<String, String> entry : databaseOrmCompatibilitySettings.entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();
            if (!runtimeSettingsBuilder.isConfigured(key)) {
                log.warnf("Persistence-unit [%1$s] - %2$s compatibility: setting '%3$s=%4$s'."
                        + " This affects Hibernate ORM's behavior and schema compatibility"
                        + " and may stop working in future versions of Quarkus.",
                        persistenceUnitName,
                        databaseOrmCompatibilityVersion.externalRepresentation,
                        key, value);
                runtimeSettingsBuilder.put(key, value);
            }
        }

        return runtimeSettingsBuilder.build();
    }

    private void unzipZipFilesAndReplaceZipsInImportFiles(Builder runtimeSettingsBuilder) {
        String newValue = SchemaToolingUtil.unzipZipFilesAndReplaceZips(
                (String) runtimeSettingsBuilder.get(AvailableSettings.JAKARTA_HBM2DDL_LOAD_SCRIPT_SOURCE));
        runtimeSettingsBuilder.put(AvailableSettings.JAKARTA_HBM2DDL_LOAD_SCRIPT_SOURCE, newValue);
    }

    private StandardServiceRegistry rewireMetadataAndExtractServiceRegistry(String persistenceUnitName, RecordedState rs,
            HibernateOrmRuntimeConfigPersistenceUnit puConfig, RuntimeSettings runtimeSettings) {
        PreconfiguredServiceRegistryBuilder serviceRegistryBuilder = new PreconfiguredServiceRegistryBuilder(
                persistenceUnitName, rs, puConfig);

        runtimeSettings.getSettings().forEach((key, value) -> {
            serviceRegistryBuilder.applySetting(key, value);
        });

        Set<Class<?>> runtimeInitiatedServiceClasses = new HashSet<>();
        for (HibernateOrmIntegrationRuntimeDescriptor descriptor : integrationRuntimeDescriptors
                .getOrDefault(persistenceUnitName, Collections.emptyList())) {
            Optional<HibernateOrmIntegrationRuntimeInitListener> listenerOptional = descriptor.getInitListener();
            if (listenerOptional.isPresent()) {
                for (StandardServiceInitiator<?> serviceInitiator : listenerOptional.get().contributeServiceInitiators()) {
                    Class<? extends Service> serviceClass = serviceInitiator.getServiceInitiated();
                    runtimeInitiatedServiceClasses.add(serviceClass);
                    serviceRegistryBuilder.addInitiator(serviceInitiator);
                }
            }
        }

        for (ProvidedService<?> providedService : rs.getProvidedServices()) {
            if (!runtimeInitiatedServiceClasses.contains(providedService.getServiceRole())) {
                serviceRegistryBuilder.addService(providedService);
            }
        }

        StandardServiceRegistryImpl standardServiceRegistry = serviceRegistryBuilder.buildNewServiceRegistry();
        return standardServiceRegistry;
    }

    private boolean isProvider(PersistenceUnitDescriptor persistenceUnit) {
        Map<Object, Object> props = Collections.emptyMap();
        String requestedProviderName = extractRequestedProviderName(persistenceUnit, props);
        if (requestedProviderName == null) {
            // We'll always assume we are the best possible provider match unless the user
            // explicitly asks for a different one.
            return true;
        }
        return FastBootHibernatePersistenceProvider.class.getName().equals(requestedProviderName)
                || "org.hibernate.jpa.HibernatePersistenceProvider".equals(requestedProviderName);
    }

    @SuppressWarnings("rawtypes")
    public static String extractRequestedProviderName(PersistenceUnitDescriptor persistenceUnit, Map integration) {
        final String integrationProviderName = extractProviderName(integration);
        if (integrationProviderName != null) {
            log.debugf("Integration provided explicit PersistenceProvider [%s]", integrationProviderName);
            return integrationProviderName;
        }

        final String persistenceUnitRequestedProvider = extractProviderName(persistenceUnit);
        if (persistenceUnitRequestedProvider != null) {
            log.debugf("Persistence-unit [%s] requested PersistenceProvider [%s]", persistenceUnit.getName(),
                    persistenceUnitRequestedProvider);
            return persistenceUnitRequestedProvider;
        }

        // NOTE : if no provider requested we assume we are the provider (the calls got
        // to us somehow...)
        log.debug("No PersistenceProvider explicitly requested, assuming Hibernate");
        return FastBootHibernatePersistenceProvider.class.getName();
    }

    @SuppressWarnings("rawtypes")
    private static String extractProviderName(Map integration) {
        if (integration == null) {
            return null;
        }
        final String setting = (String) integration.get(AvailableSettings.JAKARTA_PERSISTENCE_PROVIDER);
        return setting == null ? null : setting.trim();
    }

    private static String extractProviderName(PersistenceUnitDescriptor persistenceUnit) {
        final String persistenceUnitRequestedProvider = persistenceUnit.getProviderClassName();
        return persistenceUnitRequestedProvider == null ? null : persistenceUnitRequestedProvider.trim();
    }

    @SuppressWarnings("rawtypes")
    private void verifyProperties(Map properties) {
        if (properties != null && properties.size() != 0) {
            throw new PersistenceException(
                    "The FastbootHibernateProvider PersistenceProvider can not support runtime provided properties. "
                            + "Make sure you set all properties you need in the configuration resources before building the application.");
        }
    }

    private static void injectDataSource(String persistenceUnitName, String dataSourceName,
            MultiTenancyStrategy multiTenancyStrategy,
            RuntimeSettings.Builder runtimeSettingsBuilder) {
        // Note: the counterpart of this code, but for multitenancy (injecting the connection provider),
        // can be found in io.quarkus.hibernate.orm.runtime.boot.FastBootMetadataBuilder.mergeSettings

        if (runtimeSettingsBuilder.isConfigured(AvailableSettings.URL) ||
                runtimeSettingsBuilder.isConfigured(AvailableSettings.DATASOURCE) ||
                runtimeSettingsBuilder.isConfigured(AvailableSettings.JPA_JTA_DATASOURCE) ||
                runtimeSettingsBuilder.isConfigured(AvailableSettings.JPA_NON_JTA_DATASOURCE) ||
                runtimeSettingsBuilder.isConfigured(AvailableSettings.JAKARTA_JTA_DATASOURCE) ||
                runtimeSettingsBuilder.isConfigured(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE)) {
            // the datasource has been defined in the persistence unit, we can bail out
            return;
        }

        if (multiTenancyStrategy != null && multiTenancyStrategy != MultiTenancyStrategy.NONE
                && multiTenancyStrategy != MultiTenancyStrategy.DISCRIMINATOR) {
            // Nothing to do: the datasource retrieval will be handled dynamically
            // by an implementation of TenantConnectionResolver -- for instance
            // io.quarkus.hibernate.orm.runtime.tenant.DataSourceTenantConnectionResolver.
            // In the case of database multi-tenancy, that connection resolver
            // might not even use the assigned datasource.
            return;
        }

        DataSource dataSource;
        try {
            // ClientProxy.unwrap is necessary to trigger exceptions on inactive datasources
            dataSource = ClientProxy.unwrap(AgroalDataSourceUtil.dataSourceInstance(dataSourceName).get());
        } catch (RuntimeException e) {
            throw PersistenceUnitUtil.unableToFindDataSource(persistenceUnitName, dataSourceName, e);
        }
        runtimeSettingsBuilder.put(AvailableSettings.DATASOURCE, dataSource);
    }

    private static void injectRuntimeConfiguration(HibernateOrmRuntimeConfigPersistenceUnit persistenceUnitConfig,
            Builder runtimeSettingsBuilder) {
        // Database
        runtimeSettingsBuilder.put(AvailableSettings.JAKARTA_HBM2DDL_DATABASE_ACTION,
                persistenceUnitConfig.database().generation().generation()
                        .orElse(persistenceUnitConfig.schemaManagement().strategy()));

        runtimeSettingsBuilder.put(AvailableSettings.JAKARTA_HBM2DDL_CREATE_SCHEMAS,
                String.valueOf(persistenceUnitConfig.database().generation().createSchemas()
                        .orElse(persistenceUnitConfig.schemaManagement().createSchemas())));

        if (persistenceUnitConfig.database().generation().haltOnError()
                .orElse(persistenceUnitConfig.schemaManagement().haltOnError())) {
            runtimeSettingsBuilder.put(AvailableSettings.HBM2DDL_HALT_ON_ERROR, "true");
        }

        //Never append on existing scripts:
        runtimeSettingsBuilder.put(AvailableSettings.HBM2DDL_SCRIPTS_CREATE_APPEND, "false");

        runtimeSettingsBuilder.put(AvailableSettings.JAKARTA_HBM2DDL_SCRIPTS_ACTION,
                persistenceUnitConfig.scripts().generation().generation());

        if (persistenceUnitConfig.scripts().generation().createTarget().isPresent()) {
            runtimeSettingsBuilder.put(AvailableSettings.HBM2DDL_SCRIPTS_CREATE_TARGET,
                    persistenceUnitConfig.scripts().generation().createTarget().get());
        }

        if (persistenceUnitConfig.scripts().generation().dropTarget().isPresent()) {
            runtimeSettingsBuilder.put(AvailableSettings.HBM2DDL_SCRIPTS_DROP_TARGET,
                    persistenceUnitConfig.scripts().generation().dropTarget().get());
        }

        persistenceUnitConfig.database().defaultCatalog().ifPresent(
                catalog -> runtimeSettingsBuilder.put(AvailableSettings.DEFAULT_CATALOG, catalog));

        persistenceUnitConfig.database().defaultSchema().ifPresent(
                schema -> runtimeSettingsBuilder.put(AvailableSettings.DEFAULT_SCHEMA, schema));

        // Logging
        if (persistenceUnitConfig.log().sql()) {
            runtimeSettingsBuilder.put(AvailableSettings.SHOW_SQL, "true");

            if (persistenceUnitConfig.log().formatSql()) {
                runtimeSettingsBuilder.put(AvailableSettings.FORMAT_SQL, "true");
            }

            if (persistenceUnitConfig.log().highlightSql()) {
                runtimeSettingsBuilder.put(AvailableSettings.HIGHLIGHT_SQL, "true");
            }
        }

        if (persistenceUnitConfig.log().jdbcWarnings().isPresent()) {
            runtimeSettingsBuilder.put(AvailableSettings.LOG_JDBC_WARNINGS,
                    persistenceUnitConfig.log().jdbcWarnings().get().toString());
        }

        if (persistenceUnitConfig.log().queriesSlowerThanMs().isPresent()) {
            runtimeSettingsBuilder.put(AvailableSettings.LOG_SLOW_QUERY,
                    persistenceUnitConfig.log().queriesSlowerThanMs().get());
        }

        runtimeSettingsBuilder.put(HibernateHints.HINT_FLUSH_MODE,
                persistenceUnitConfig.flush().mode());
    }

}
