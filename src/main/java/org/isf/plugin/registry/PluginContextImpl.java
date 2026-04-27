/*
 * Open Hospital (www.open-hospital.org)
 * Copyright © 2006-2026 Informatici Senza Frontiere (info@informaticisenzafrontiere.org)
 *
 * Open Hospital is a free and open source software for healthcare data management.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * https://www.gnu.org/licenses/gpl-3.0-standalone.html
 */
package org.isf.plugin.registry;

import org.isf.plugin.event.OHPluginEvent;
import org.isf.plugin.event.PluginEventBus;
import org.isf.plugin.model.PermissionCheckResult;
import org.isf.plugin.model.PluginCapability;
import org.isf.plugin.model.PluginDescriptor;
import org.isf.plugin.model.PluginPermission;
import org.isf.plugin.model.field.PatientField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Concrete implementation of {@link PluginContext} used at runtime.
 * Each active plugin receives its own isolated instance.
 */
public class PluginContextImpl implements PluginContext {

    private static final Logger LOG = LoggerFactory.getLogger(PluginContextImpl.class);

    private final PluginDescriptor     descriptor;
    final         PluginEventBusImpl   eventBus;    // package-private — PluginRegistryImpl access
    private final PluginFileAccessImpl fileAccess;
    private final Logger               pluginLogger;

    // TODO Phase 3 — add PatientBrowserManager field and inject via constructor:
    //   private final PatientBrowserManager patientBrowserManager;
    // Pass it from PluginRegistryImpl.loadAndStart() where the context is constructed.
    // PluginRegistryImpl is a Spring @Component so it can @Autowired PatientBrowserManager.

    PluginContextImpl(PluginDescriptor descriptor, Path pluginLogDir) {
        this.descriptor   = descriptor;
        this.eventBus     = new PluginEventBusImpl(descriptor.getPluginId());
        this.fileAccess   = new PluginFileAccessImpl(pluginLogDir);
        this.pluginLogger = LoggerFactory.getLogger(descriptor.getPluginId());
    }

    @Override public PluginDescriptor getDescriptor() { return descriptor; }
    @Override public PluginEventBus   eventBus()      { return eventBus; }
    @Override public Logger           logger()        { return pluginLogger; }

    @Override
    public PluginFileAccess files() {
        if (!descriptor.getCapabilities().contains(PluginCapability.LOG_FILE_WRITE)) {
            throw new UnsupportedOperationException(
                "Plugin '" + descriptor.getPluginId() +
                "' did not declare PluginCapability.LOG_FILE_WRITE");
        }
        return fileAccess;
    }

    @Override public PluginDataAccessor data()     { return new PluginDataAccessorImpl(); }
    @Override public PluginConfig       config()   { return new PluginConfigImpl(); }

    @Override
    public PluginHttpClient httpClient() {
        // TODO Phase 3 — implement an allowlist-enforced HTTP client.
        // Validate the target host against descriptor.getExternalConnections()
        // before opening any connection. Throw SecurityException if not declared.
        throw new UnsupportedOperationException("PluginHttpClient — Phase 3");
    }

    @Override
    public ManagerExtensionRegistry managers() {
        // TODO Phase 3 — implement chain-of-responsibility extension on OH-core Managers.
        // Registry must track extensions per plugin and unregisterAll() on shutdown().
        throw new UnsupportedOperationException("ManagerExtensionRegistry — Phase 3");
    }

    @Override
    public PermissionCheckResult check(PluginPermission permission) {
        if (!descriptor.getPermissions().contains(permission)) {
            return PermissionCheckResult.DENIED_PLUGIN;
        }
        // TODO Phase 3 — check current user's OH role via SecurityContextHolder:
        //   Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        //   if (!userHasRole(auth, permission)) return PermissionCheckResult.DENIED_USER;
        return PermissionCheckResult.GRANTED;
    }

    void shutdown() {
        eventBus.unsubscribeAll();
    }

    // =========================================================================
    // PluginEventBusImpl
    // =========================================================================

    static final class PluginEventBusImpl implements PluginEventBus {

        private final String pluginId;

        @SuppressWarnings("rawtypes")
        private final List<Sub> subs = new CopyOnWriteArrayList<>();

        PluginEventBusImpl(String pluginId) { this.pluginId = pluginId; }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public <E extends OHPluginEvent> void subscribe(Class<E> type, Consumer<E> handler) {
            subs.add(new Sub(type, handler));
            LOG.debug("Plugin '{}' subscribed to {}", pluginId, type.getSimpleName());
        }

        @Override
        @SuppressWarnings("rawtypes")
        public <E extends OHPluginEvent> void unsubscribe(Class<E> type) {
            subs.removeIf(s -> s.type().equals(type));
        }

        @Override
        public void unsubscribeAll() { subs.clear(); }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public void publish(OHPluginEvent event) {
            for (Sub s : subs) {
                if (s.type().isInstance(event)) {
                    try {
                        s.handler().accept(event);
                    } catch (Exception ex) {
                        LOG.error("Plugin '{}' handler threw on {}", pluginId,
                            event.getClass().getSimpleName(), ex);
                    }
                }
            }
        }

        /** Called by PluginRegistryImpl to fan out domain events. */
        void publishDomainEvent(OHPluginEvent event) { publish(event); }

        @SuppressWarnings("rawtypes")
        private record Sub(Class type, Consumer handler) {}
    }

    // =========================================================================
    // PluginFileAccessImpl
    // =========================================================================

    static final class PluginFileAccessImpl implements PluginFileAccess {

        private final Path logDir;

        PluginFileAccessImpl(Path logDir) { this.logDir = logDir; }

        @Override
        public Writer openLogWriter(String relativePath, boolean append) throws IOException {
            if (relativePath == null || relativePath.isBlank())
                throw new IllegalArgumentException("relativePath must not be blank");
            if (relativePath.contains(".."))
                throw new IllegalArgumentException("relativePath must not contain '..': " + relativePath);
            if (Path.of(relativePath).isAbsolute())
                throw new IllegalArgumentException("relativePath must be relative: " + relativePath);
            Path target = logDir.resolve(relativePath).normalize();
            if (!target.startsWith(logDir))
                throw new IllegalArgumentException("Path escapes log directory: " + relativePath);
            Files.createDirectories(target.getParent());
            return new FileWriter(target.toFile(), append);
        }

        @Override
        public Path logDirectory() { return logDir; }
    }

    // =========================================================================
    // PluginDataAccessorImpl
    // =========================================================================

    private class PluginDataAccessorImpl implements PluginDataAccessor {

        @Override
        public void withPatient(int patientCode, Consumer<PatientView> consumer)
                throws PluginAuthorizationException {
            if (!descriptor.getPermissions().contains(PluginPermission.READ_PATIENT)) {
                throw new PluginAuthorizationException(
                    descriptor.getPluginId(),
                    PluginPermission.READ_PATIENT,
                    PermissionCheckResult.DENIED_PLUGIN);
            }
            // TODO Phase 3 — verify READ_PATIENT against OH_PLUGIN_APPROVAL, not just the
            //   in-memory descriptor. OH_PLUGIN_APPROVAL is the authoritative record of what
            //   the admin actually approved at install time. If the two diverge (e.g. the
            //   stored manifest was tampered), the approval table wins and access is denied:
            //   boolean approved = approvalRepository.existsByPluginAndApprovalTypeAndItemKey(
            //       pluginEntity, ApprovalType.PERMISSION, PluginPermission.READ_PATIENT.name());
            //   if (!approved) throw new PluginAuthorizationException(..., DENIED_PLUGIN);
            Set<String> allowed = descriptor.getFieldPermissions().stream()
                .filter(fp -> "Patient".equals(fp.getDomainName()))
                .flatMap(fp -> fp.fieldNames().stream())
                .collect(Collectors.toSet());
            // TODO Phase 3 — load real patient from DB:
            //   Patient patient = patientBrowserManager.getPatientById(patientCode);
            //   if (patient == null) { consumer.accept(new FilteredPatientView(patientCode, allowed, null)); return; }
            // Then pass the loaded patient to FilteredPatientView so it can return real values.
            // Also write an audit record here: pluginId, userId, patientCode, timestamp, purpose.
            consumer.accept(new FilteredPatientView(patientCode, allowed));
        }
    }

    // =========================================================================
    // FilteredPatientView
    // =========================================================================

    private static final class FilteredPatientView implements PatientView {

        private final int         code;
        private final Set<String> allowed;

        FilteredPatientView(int code, Set<String> allowed) {
            this.code    = code;
            this.allowed = allowed;
        }

        @Override public int    getPatientCode() { return code; }
        @Override public String getFirstName()   { return f(PatientField.FIRST_NAME); }
        @Override public String getLastName()    { return f(PatientField.LAST_NAME); }
        @Override public String getBirthDate()   { return f(PatientField.BIRTH_DATE); }
        @Override public String getSex()         { return f(PatientField.SEX); }
        @Override public String getAddress()     { return f(PatientField.ADDRESS); }
        @Override public String getTelephone()   { return f(PatientField.TELEPHONE); }
        @Override public String getBloodType()   { return f(PatientField.BLOOD_TYPE); }
        @Override public String getWardCode()    { return f(PatientField.WARD_CODE); }

        private String f(PatientField field) {
            if (!allowed.contains(field.fieldName())) return null;
            // TODO Phase 3 — return real value from loaded Patient entity:
            //   return switch (field) {
            //       case FIRST_NAME -> patient.getFirstName();
            //       case LAST_NAME  -> patient.getLastName();
            //       case BIRTH_DATE -> patient.getBirthDate() != null ? patient.getBirthDate().toString() : null;
            //       ... etc
            //   };
            return "[TODO: DB]";
        }
    }

    // =========================================================================
    // PluginConfigImpl
    // =========================================================================

    private static final class PluginConfigImpl implements PluginConfig {
        // TODO Phase 3 — read from plugin-{pluginId}.properties in OH config directory.
        // Add pluginId field and load Properties from ConfigurationProperties or
        // from a file at ${oh.plugin.config.dir}/{pluginId}.properties.
        @Override public String  getString(String k, String d)  { return d; }
        @Override public int     getInt(String k, int d)        { return d; }
        @Override public boolean getBoolean(String k, boolean d){ return d; }
    }
}
