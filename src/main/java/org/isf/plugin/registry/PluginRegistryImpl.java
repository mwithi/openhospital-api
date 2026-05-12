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

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.isf.plugin.event.OHDomainEvents;
import org.isf.plugin.event.OHPluginEvent;
import org.isf.plugin.manager.ManifestJsonReader;
import org.isf.plugin.manager.ManifestVerifier;
import org.isf.plugin.model.OhPlugin;
import org.isf.plugin.model.OhPlugin.PluginStatus;
import org.isf.plugin.model.OhPluginEvent;
import org.isf.plugin.model.OhPluginEvent.EventType;
import org.isf.plugin.model.PluginDescriptor;
import org.isf.plugin.service.PluginEventRepository;
import org.isf.plugin.service.PluginRepository;
import org.isf.plugin.spi.OHPlugin;
import org.isf.plugin.spi.OHPluginLifecycleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Loads, starts, and manages all active OH plugins at runtime.
 *
 * <h3>Startup sequence</h3> On {@link PostConstruct}, reads all {@link PluginStatus#ACTIVE} plugins from {@code OH_PLUGIN}, loads their JAR from the staging
 * directory, instantiates the {@link OHPlugin} implementation via {@link java.util.ServiceLoader}, calls {@code onInstall()} if never run before, then calls
 * {@code onStart()}.
 *
 * <h3>Shutdown sequence</h3> On {@link PreDestroy}, calls {@code onStop()} on every running plugin in reverse load order.
 *
 * <h3>Event publishing</h3> Domain events (PatientCreated, PatientAdmitted, …) are published via {@link #publishEvent(OHDomainEvents)}. Call this from OH-core
 * Managers (or from a Spring ApplicationEvent listener bridging them) to fan out to all interested plugin event buses.
 *
 * <h3>Current limitations (Phase 2)</h3>
 * <ul>
 * <li>Uses a shared classloader per plugin JAR — no bytecode isolation beyond separate URLClassLoader instances.</li>
 * <li>No network allowlist enforcement on the classloader level yet.</li>
 * </ul>
 */
@Component
public class PluginRegistryImpl {

	private static final Logger LOG = LoggerFactory.getLogger(PluginRegistryImpl.class);

	private final PluginRepository pluginRepository;
	private final PluginEventRepository eventRepository;

	@Value("${oh.plugin.staging.dir:#{systemProperties['java.io.tmpdir']}/oh-plugins-staging}")
	private String stagingDir;

	@Value("${oh.plugin.log.dir:#{systemProperties['java.io.tmpdir']}/oh-plugins-logs}")
	private String logDir;

	/** pluginId → running plugin entry */
	private final Map<String, RunningPlugin> running = new ConcurrentHashMap<>();

	// -------------------------------------------------------------------------

	public PluginRegistryImpl(PluginRepository pluginRepository,
		PluginEventRepository eventRepository) {
		this.pluginRepository = pluginRepository;
		this.eventRepository = eventRepository;
	}

	// -------------------------------------------------------------------------
	// Lifecycle
	// -------------------------------------------------------------------------

	@PostConstruct
	public void startAll() {
		List<OhPlugin> activePlugins;
		try {
			activePlugins = pluginRepository.findAll().stream()
				.filter(p -> p.getStatus() == PluginStatus.ACTIVE)
				.toList();
		} catch (CannotCreateTransactionException | DataAccessException e) {
			LOG.warn("PluginRegistry — database unavailable, skipping plugin startup: {}",
				e.getMessage());
			return;
		}
		LOG.info("PluginRegistry — loading {} active plugin(s)", activePlugins.size());

		for (OhPlugin plugin : activePlugins) {
			try {
				loadAndStart(plugin);
			} catch (Exception e) {
				LOG.error("Failed to start plugin '{}' — marking as FAILED",
					plugin.getPluginId(), e);
				plugin.setStatus(PluginStatus.FAILED);
				pluginRepository.save(plugin);
				eventRepository.save(new OhPluginEvent(
					plugin, EventType.FAILED,
					LocalDateTime.now(), "system",
					e.getMessage()));
			}
		}

		LOG.info("PluginRegistry — {} plugin(s) started successfully", running.size());
	}

	@PreDestroy
	public void stopAll() {
		LOG.info("PluginRegistry — stopping {} plugin(s)", running.size());
		running.values().forEach(rp -> {
			try {
				rp.plugin().onStop(rp.context());
				rp.context().shutdown();
				LOG.info("Plugin '{}' stopped", rp.ohPlugin().getPluginId());
			} catch (Exception e) {
				LOG.error("Error stopping plugin '{}'", rp.ohPlugin().getPluginId(), e);
			}
		});
		running.clear();
	}

	// -------------------------------------------------------------------------
	// Single plugin lifecycle
	// -------------------------------------------------------------------------

	/**
	 * Stops a single running plugin and closes its classloader.
	 *
	 * <p>
	 * Closing the {@link URLClassLoader} is essential on Windows — without it the JVM keeps the JAR file locked and the OS prevents moving or deleting it.
	 *
	 * <p>
	 * Called by {@link org.isf.plugin.rest.PluginController} before moving plugin files between status directories (disable, enable, update).
	 *
	 * @return {@code true} if the plugin was running and has been stopped, {@code false} if the plugin was not running
	 */
	public boolean stopPlugin(String pluginId) {
		RunningPlugin rp = running.remove(pluginId);
		if (rp == null) {
			LOG.debug("stopPlugin('{}') — plugin was not running", pluginId);
			return false;
		}
		try {
			rp.plugin().onStop(rp.context());
			rp.context().shutdown();
		} catch (Exception e) {
			LOG.error("Error during onStop() for plugin '{}'", pluginId, e);
		}
		try {
			rp.classLoader().close(); // releases JAR file lock on Windows
			LOG.debug("Classloader closed for plugin '{}'", pluginId);
		} catch (java.io.IOException e) {
			LOG.warn("Could not close classloader for plugin '{}': {}", pluginId, e.getMessage());
		}
		LOG.info("Plugin '{}' stopped", pluginId);
		return true;
	}

	/**
	 * Returns the {@link OHPlugin} instance for the given pluginId, or {@code null} if the plugin is not running.
	 */
	public OHPlugin getPluginInstance(String pluginId) {
		RunningPlugin rp = running.get(pluginId);
		return rp != null ? rp.plugin() : null;
	}

	// -------------------------------------------------------------------------
	// Event publishing
	// -------------------------------------------------------------------------

	/**
	 * Publishes a domain event to all running plugins that have subscribed to it.
	 *
	 * <p>
	 * Call this from an OH-core event bridge (e.g. a Spring {@code ApplicationListener} on {@code PatientCreatedEvent}).
	 *
	 * <p>
	 * TODO Phase 3 — consider async dispatch via a bounded queue so that a slow plugin handler does not block the OH-core thread that published the event. Use
	 * a per-plugin single-threaded executor to preserve event ordering.
	 *
	 * @param event the domain event to publish
	 */
	public void publishEvent(OHPluginEvent event) {
		if (running.isEmpty())
			return;
		LOG.debug("Publishing {} to {} plugin(s)",
			event.getClass().getSimpleName(), running.size());
		for (RunningPlugin rp : running.values()) {
			rp.context().eventBus.publishDomainEvent(event);
		}
	}

	/**
	 * Returns {@code true} if the given plugin is currently running.
	 */
	public boolean isRunning(String pluginId) {
		return running.containsKey(pluginId);
	}

	// =========================================================================
	// Private helpers
	// =========================================================================

	private void loadAndStart(OhPlugin ohPlugin) throws Exception {
		String pluginId = ohPlugin.getPluginId();
		LOG.info("Loading plugin '{}' v{}", pluginId, ohPlugin.getVersion());

		// 1. Resolve JAR path
		Path jarPath = resolveJar(ohPlugin);

		// 2. Verify the JAR still contains the same manifest reviewed at upload
		ManifestVerifier.verifyJarManifestMatches(ohPlugin.getManifestJson(), jarPath);

		// 3. Deserialize descriptor from stored manifest JSON
		PluginDescriptor descriptor = ManifestJsonReader.read(ohPlugin.getManifestJson());

		// 4. Create isolated classloader
		URLClassLoader classLoader = new URLClassLoader(
			new URL[] { jarPath.toUri().toURL() },
			Thread.currentThread().getContextClassLoader());

		// 5. Discover OHPlugin implementation via ServiceLoader
		OHPlugin plugin = loadPlugin(classLoader, pluginId);

		// 6. Prepare log directory
		Path pluginLogDir = Path.of(logDir, pluginId);
		Files.createDirectories(pluginLogDir);

		// 7. Build context
		PluginContextImpl ctx = new PluginContextImpl(descriptor, pluginLogDir);

		// TODO Phase 3 — call onInstall() only on first activation (no INSTALLED event yet):
		// if (!eventRepository.existsByPluginAndEventType(ohPlugin, EventType.INSTALLED)) {
		// plugin.onInstall(ctx);
		// }

		// TODO Phase 3 — run per-plugin Flyway migrations before onStart():
		// if plugin declares DB_MIGRATION capability, scan JAR for
		// db/migration/p_{pluginId}_*.sql and apply via Flyway with pluginId prefix.

		// 8. onStart
		try {
			plugin.onStart(ctx);
		} catch (OHPluginLifecycleException e) {
			throw new RuntimeException("onStart() failed for plugin '" + pluginId + "'", e);
		}

		// 9. Register as running
		running.put(pluginId, new RunningPlugin(ohPlugin, plugin, ctx, classLoader));

		// 10. Record STARTED event
		eventRepository.save(new OhPluginEvent(
			ohPlugin, EventType.STARTED,
			LocalDateTime.now(), "system",
			"Started on OH startup"));

		LOG.info("Plugin '{}' v{} started successfully — log dir: {}",
			pluginId, ohPlugin.getVersion(), pluginLogDir);
	}

	private Path resolveJar(OhPlugin ohPlugin) {
		// Primary: stored JAR path (already reflects current status dir after approve/enable)
		if (ohPlugin.getJarPath() != null) {
			Path p = Path.of(ohPlugin.getJarPath());
			if (Files.exists(p))
				return p;
			LOG.warn("Plugin '{}' stored JAR path not found: {} — searching subdirs",
				ohPlugin.getPluginId(), p);
		}
		// Search status subdirectories — ACTIVE plugins should be in active/
		String pluginId = ohPlugin.getPluginId();
		for (String status : new String[] { "active", "validating", "disabled" }) {
			Path candidate = Path.of(stagingDir, status, pluginId, pluginId + ".jar");
			if (Files.exists(candidate)) {
				LOG.debug("Plugin '{}' JAR found in {}/", pluginId, status);
				return candidate;
			}
		}
		// Legacy fallback — flat staging dir (pre-split installs)
		Path legacy = Path.of(stagingDir, pluginId + ".jar");
		if (Files.exists(legacy)) {
			LOG.warn("Plugin '{}' using legacy flat staging path: {}", pluginId, legacy);
			return legacy;
		}
		throw new IllegalStateException(
			"JAR not found for plugin '" + pluginId + "'.");
	}

	private OHPlugin loadPlugin(URLClassLoader classLoader, String pluginId) {
		java.util.ServiceLoader<OHPlugin> loader = java.util.ServiceLoader.load(OHPlugin.class, classLoader);
		for (OHPlugin p : loader) {
			return p;
		}
		throw new IllegalStateException(
			"No OHPlugin implementation found in JAR for plugin '" + pluginId +
				"'. Check META-INF/services/org.isf.plugin.spi.OHPlugin");
	}

	// -------------------------------------------------------------------------
	// Internal record
	// -------------------------------------------------------------------------

	private record RunningPlugin(
		OhPlugin ohPlugin,
		OHPlugin plugin,
		PluginContextImpl context,
		URLClassLoader classLoader) {
	}
}
