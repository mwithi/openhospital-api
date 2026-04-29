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
package org.isf.plugin.rest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.isf.plugin.dto.PluginDTO;
import org.isf.plugin.dto.PluginInstallProposalDTO;
import org.isf.plugin.manager.ManifestJsonReader;
import org.isf.plugin.manager.ManifestVerifier;
import org.isf.plugin.mapper.PluginMapper;
import org.isf.plugin.model.OhPlugin;
import org.isf.plugin.model.OhPlugin.PluginStatus;
import org.isf.plugin.model.OhPluginEvent;
import org.isf.plugin.model.OhPluginEvent.EventType;
import org.isf.plugin.model.PluginDescriptor;
import org.isf.plugin.service.PluginApprovalRepository;
import org.isf.plugin.service.PluginEventRepository;
import org.isf.plugin.service.PluginRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST controller for the Open Hospital plugin system.
 *
 * <h3>Install flow</h3>
 * <ol>
 * <li>{@code POST /api/plugins/install} — upload ZIP, validate manifest, return {@link PluginInstallProposalDTO} for admin review. Plugin is saved with status
 * {@code VALIDATING}.</li>
 * <li>{@code PUT /api/plugins/{pluginId}/approve} — admin approves, plugin is activated (onInstall → onStart → ACTIVE).</li>
 * </ol>
 */
@RestController
@RequestMapping(value = "/plugins", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Plugins", description = "Manage Open Hospital plugins")
@SecurityRequirement(name = "bearerAuth")
public class PluginController {

	private static final Logger LOGGER = LoggerFactory.getLogger(PluginController.class);

	private final PluginRepository pluginRepository;
	private final PluginApprovalRepository approvalRepository;
	private final PluginEventRepository eventRepository;
	private final PluginMapper pluginMapper;

	@Value("${oh.plugin.staging.dir:#{systemProperties['java.io.tmpdir']}/oh-plugins-staging}")
	private String stagingDir;

	public PluginController(PluginRepository pluginRepository,
		PluginApprovalRepository approvalRepository,
		PluginEventRepository eventRepository,
		PluginMapper pluginMapper) {
		this.pluginRepository = pluginRepository;
		this.approvalRepository = approvalRepository;
		this.eventRepository = eventRepository;
		this.pluginMapper = pluginMapper;
	}

	// -------------------------------------------------------------------------
	// GET /plugins
	// -------------------------------------------------------------------------

	@GetMapping
	@Operation(summary = "List all installed plugins")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Plugin list returned"),
			@ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content)
	})
	public ResponseEntity<List<PluginDTO>> listPlugins() {
		List<OhPlugin> plugins = pluginRepository.findAllByOrderByNameAsc();
		return ResponseEntity.ok(pluginMapper.toDTOList(plugins));
	}

	// -------------------------------------------------------------------------
	// GET /plugins/{pluginId}
	// -------------------------------------------------------------------------

	@GetMapping("/{pluginId}")
	@Operation(summary = "Get a single plugin by ID")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Plugin found"),
			@ApiResponse(responseCode = "404", description = "Plugin not found", content = @Content),
			@ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content)
	})
	public ResponseEntity<PluginDTO> getPlugin(
		@Parameter(description = "Plugin identifier", required = true) @PathVariable String pluginId) {
		return pluginRepository.findById(pluginId)
			.map(plugin -> ResponseEntity.ok(pluginMapper.toDTO(plugin)))
			.orElse(ResponseEntity.notFound().build());
	}

	// -------------------------------------------------------------------------
	// GET /plugins/{pluginId}/manifest
	// -------------------------------------------------------------------------

	@GetMapping("/{pluginId}/manifest")
	public ResponseEntity< ? > getPluginManifest(@PathVariable String pluginId) {
		return pluginRepository.findById(pluginId)
			.map(plugin -> {
				try {
					PluginDescriptor descriptor = ManifestJsonReader.read(plugin.getManifestJson());
					return ResponseEntity.ok(pluginMapper.toProposalDTO(descriptor));
				} catch (Exception e) {
					return ResponseEntity.internalServerError().body("Cannot parse manifest");
				}
			})
			.orElse(ResponseEntity.notFound().build());
	}

	// -------------------------------------------------------------------------
	// POST /plugins/install
	// -------------------------------------------------------------------------

	@PostMapping(value = "/install", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(summary = "Upload a plugin ZIP for validation", description = "Validates the manifest and returns an install proposal " +
		"for administrator review. The plugin is not activated yet.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Proposal ready for review", content = @Content(schema = @Schema(implementation = PluginInstallProposalDTO.class))),
			@ApiResponse(responseCode = "400", description = "Invalid ZIP or manifest", content = @Content),
			@ApiResponse(responseCode = "409", description = "Plugin already installed", content = @Content),
			@ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content)
	})
	public ResponseEntity< ? > installPlugin(
		@Parameter(description = "Plugin ZIP file", required = true) @RequestParam("file") MultipartFile file) {

		String currentUser = currentUsername();

		// 1. Extract manifest.json from the ZIP
		ExtractedZip extracted;
		try {
			extracted = extractZip(file);
		} catch (IllegalArgumentException e) {
			LOGGER.warn("Plugin upload rejected: {}", e.getMessage());
			return ResponseEntity.badRequest().body(e.getMessage());
		} catch (IOException e) {
			LOGGER.error("Failed to read plugin ZIP", e);
			return ResponseEntity.badRequest().body("Failed to read ZIP: " + e.getMessage());
		}

		// 2. Check for duplicate
		PluginDescriptor descriptor = extracted.descriptor();

		if (pluginRepository.existsByPluginId(descriptor.getPluginId())) {
			return ResponseEntity.status(HttpStatus.CONFLICT)
				.body("Plugin '" + descriptor.getPluginId() +
					"' is already installed. Use the update endpoint instead.");
		}

		// 3. Save ZIP to staging directory
		Path jarPath;
		try {
			jarPath = saveToStaging(descriptor.getPluginId(), extracted.jarBytes());
			saveFrontendToStaging(descriptor.getPluginId(), extracted.frontendFiles());
		} catch (IOException e) {
			LOGGER.error("Failed to save plugin files to staging", e);
			return ResponseEntity.internalServerError()
				.body("Failed to save plugin: " + e.getMessage());
		}

		// 4. Persist with VALIDATING status
		OhPlugin plugin = new OhPlugin(
			descriptor.getPluginId(),
			descriptor.getVersion(),
			descriptor.getName(),
			PluginStatus.VALIDATING,
			LocalDateTime.now(),
			currentUser,
			jarPath.toString(),
			extracted.manifestJson());
		pluginRepository.save(plugin);

		// 5. Record UPLOADED event
		eventRepository.save(new OhPluginEvent(
			plugin, EventType.UPLOADED, LocalDateTime.now(), currentUser,
			"ZIP uploaded by " + currentUser));

		LOGGER.info("Plugin '{}' v{} uploaded by {} — awaiting approval",
			descriptor.getPluginId(), descriptor.getVersion(), currentUser);

		return ResponseEntity.ok(pluginMapper.toProposalDTO(descriptor));
	}

	// -------------------------------------------------------------------------
	// PUT /plugins/{pluginId}/approve
	// -------------------------------------------------------------------------

	@PutMapping("/{pluginId}/approve")
	@Operation(summary = "Approve and activate a VALIDATING plugin", description = "Called after the administrator has reviewed the install " +
		"proposal. Activates the plugin: onInstall → onStart → ACTIVE.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Plugin approved and activated"),
			@ApiResponse(responseCode = "404", description = "Plugin not found", content = @Content),
			@ApiResponse(responseCode = "409", description = "Plugin is not in VALIDATING status", content = @Content),
			@ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content)
	})
	public ResponseEntity< ? > approvePlugin(
		@Parameter(description = "Plugin identifier", required = true) @PathVariable String pluginId) {

		String currentUser = currentUsername();

		OhPlugin plugin = pluginRepository.findById(pluginId).orElse(null);
		if (plugin == null) {
			return ResponseEntity.notFound().build();
		}
		if (plugin.getStatus() != PluginStatus.VALIDATING) {
			return ResponseEntity.status(HttpStatus.CONFLICT)
				.body("Plugin '" + pluginId + "' is in status " +
					plugin.getStatus() + " — only VALIDATING plugins can be approved.");
		}

		// TODO Phase 3 — write OH_PLUGIN_APPROVAL rows (one per capability, permission,
		// field permission, and external connection). Currently OH_PLUGIN_APPROVAL is
		// always empty. Example for capabilities:
		// for (PluginCapability cap : descriptor.getCapabilities()) {
		// approvalRepository.save(new OhPluginApproval(
		// plugin, ApprovalType.CAPABILITY, cap.name(), currentUser, now, ""));
		// }
		// Do the same for permissions, fieldPermissions (with purpose), and connections.

		// TODO Phase 3 — instead of just marking ACTIVE here, delegate to PluginRegistryImpl:
		// pluginRegistryImpl.activatePlugin(plugin)
		// which will call onInstall() + onStart() on the real plugin instance.
		// Currently onInstall/onStart are called at startup, not at approval time.
		try {
			verifyStoredJarManifest(plugin);
		} catch (IllegalArgumentException e) {
			LOGGER.warn("Plugin '{}' approval rejected: {}", pluginId, e.getMessage());
			return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(errorBody("Plugin artifact verification failed: " + e.getMessage()));
		} catch (IOException e) {
			LOGGER.error("Failed to verify plugin '{}' JAR before approval", pluginId, e);
			return ResponseEntity.internalServerError()
				.body(errorBody("Failed to verify plugin artifact: " + e.getMessage()));
		}
		plugin.setStatus(PluginStatus.ACTIVE);
		pluginRepository.save(plugin);

		LocalDateTime now = LocalDateTime.now();
		eventRepository.save(new OhPluginEvent(
			plugin, EventType.APPROVED, now, currentUser, null));
		eventRepository.save(new OhPluginEvent(
			plugin, EventType.INSTALLED, now, currentUser, null));
		eventRepository.save(new OhPluginEvent(
			plugin, EventType.STARTED, now, currentUser, null));

		LOGGER.info("Plugin '{}' approved and activated by {}", pluginId, currentUser);
		return ResponseEntity.ok(pluginMapper.toDTO(plugin));
	}

	// -------------------------------------------------------------------------
	// PUT /plugins/{pluginId}/disable
	// -------------------------------------------------------------------------

	@PutMapping("/{pluginId}/disable")
	@Operation(summary = "Disable an ACTIVE plugin")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Plugin disabled"),
			@ApiResponse(responseCode = "404", description = "Plugin not found", content = @Content),
			@ApiResponse(responseCode = "409", description = "Plugin is not ACTIVE", content = @Content),
			@ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content)
	})
	public ResponseEntity< ? > disablePlugin(@PathVariable String pluginId) {
		return setStatus(pluginId, PluginStatus.ACTIVE, PluginStatus.DISABLED,
			EventType.DISABLED, EventType.STOPPED);
	}

	// -------------------------------------------------------------------------
	// PUT /plugins/{pluginId}/enable
	// -------------------------------------------------------------------------

	@PutMapping("/{pluginId}/enable")
	@Operation(summary = "Re-enable a DISABLED plugin")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Plugin enabled"),
			@ApiResponse(responseCode = "404", description = "Plugin not found", content = @Content),
			@ApiResponse(responseCode = "409", description = "Plugin is not DISABLED", content = @Content),
			@ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content)
	})
	public ResponseEntity< ? > enablePlugin(@PathVariable String pluginId) {
		return setStatus(pluginId, PluginStatus.DISABLED, PluginStatus.ACTIVE,
			EventType.ENABLED, EventType.STARTED);
	}

	// -------------------------------------------------------------------------
	// PUT /plugins/{pluginId}/update
	// -------------------------------------------------------------------------

	@PutMapping(value = "/{pluginId}/update", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(summary = "Update an installed plugin with a new ZIP", description = "Uploads a new version ZIP for an ACTIVE or DISABLED plugin. " +
		"If the new manifest declares additional capabilities, permissions, " +
		"field permissions, or external connections that were not in the " +
		"previous approved version, the plugin is moved to VALIDATING and " +
		"must be re-approved before activation. If the manifest is unchanged " +
		"or only removes elements, the update is applied immediately.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Update applied or pending approval", content = @Content(schema = @Schema(implementation = PluginInstallProposalDTO.class))),
			@ApiResponse(responseCode = "400", description = "Invalid ZIP or manifest", content = @Content),
			@ApiResponse(responseCode = "404", description = "Plugin not found", content = @Content),
			@ApiResponse(responseCode = "409", description = "Plugin is in VALIDATING or FAILED status", content = @Content),
			@ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content)
	})
	public ResponseEntity< ? > updatePlugin(
		@Parameter(description = "Plugin identifier", required = true) @PathVariable String pluginId,
		@Parameter(description = "New plugin ZIP file", required = true) @RequestParam("file") MultipartFile file) {

		String currentUser = currentUsername();

		OhPlugin existing = pluginRepository.findById(pluginId).orElse(null);
		if (existing == null) {
			return ResponseEntity.notFound().build();
		}
		if (existing.getStatus() == PluginStatus.VALIDATING
			|| existing.getStatus() == PluginStatus.FAILED) {
			return ResponseEntity.status(HttpStatus.CONFLICT)
				.body("Plugin '" + pluginId + "' is in status " +
					existing.getStatus() + " — resolve the current state first.");
		}

		// 1. Extract and validate new ZIP
		ExtractedZip extracted;
		try {
			extracted = extractZip(file);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(e.getMessage());
		} catch (IOException e) {
			return ResponseEntity.internalServerError()
				.body("Failed to read ZIP: " + e.getMessage());
		}

		PluginDescriptor newDescriptor = extracted.descriptor();

		// 2. Verify pluginId matches
		if (!pluginId.equals(newDescriptor.getPluginId())) {
			return ResponseEntity.badRequest()
				.body("ZIP pluginId '" + newDescriptor.getPluginId() +
					"' does not match URL pluginId '" + pluginId + "'");
		}

		// 3. Save new JAR and frontend to staging
		Path jarPath;
		try {
			jarPath = saveToStaging(pluginId, extracted.jarBytes());
			saveFrontendToStaging(pluginId, extracted.frontendFiles());
		} catch (IOException e) {
			return ResponseEntity.internalServerError()
				.body("Failed to save plugin files: " + e.getMessage());
		}

		// 4. Detect if re-approval is needed by comparing with stored manifest
		// TODO Phase 3 — implement deep manifest diff:
		// parse existing.getManifestJson() into old PluginDescriptor,
		// compare capabilities, permissions, fieldPermissions, externalConnections.
		// If new manifest has MORE than old, set needsReApproval = true.
		// For now: always require re-approval on update as a safe default.
		boolean needsReApproval = true;

		// 5. Update stored data
		existing.setVersion(newDescriptor.getVersion());
		existing.setName(newDescriptor.getName());
		existing.setJarPath(jarPath.toString());
		existing.setManifestJson(extracted.manifestJson());
		existing.setStatus(needsReApproval ? PluginStatus.VALIDATING : existing.getStatus());
		pluginRepository.save(existing);

		// 6. Record event
		LocalDateTime now = LocalDateTime.now();
		eventRepository.save(new OhPluginEvent(
			existing, EventType.UPLOADED, now, currentUser,
			"Updated to v" + newDescriptor.getVersion() + " by " + currentUser +
				(needsReApproval ? " — re-approval required" : "")));

		LOGGER.info("Plugin '{}' updated to v{} by {} — {}",
			pluginId, newDescriptor.getVersion(), currentUser,
			needsReApproval ? "awaiting re-approval" : "applied immediately");

		return ResponseEntity.ok(pluginMapper.toProposalDTO(newDescriptor));
	}

	// -------------------------------------------------------------------------
	// DELETE /plugins/{pluginId}
	// -------------------------------------------------------------------------

	@DeleteMapping("/{pluginId}")
	@Operation(summary = "Uninstall a plugin", description = "Calls onStop and onUninstall, then removes the plugin " +
		"record. Approval and event history is preserved.")
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "Plugin uninstalled"),
			@ApiResponse(responseCode = "404", description = "Plugin not found", content = @Content),
			@ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content)
	})
	public ResponseEntity<Void> uninstallPlugin(@PathVariable String pluginId) {
		String currentUser = currentUsername();

		OhPlugin plugin = pluginRepository.findById(pluginId).orElse(null);
		if (plugin == null) {
			return ResponseEntity.notFound().build();
		}

		eventRepository.save(new OhPluginEvent(
			plugin, EventType.STOPPED, LocalDateTime.now(), currentUser, null));
		eventRepository.save(new OhPluginEvent(
			plugin, EventType.UNINSTALLED, LocalDateTime.now(), currentUser, null));

		pluginRepository.delete(plugin);

		LOGGER.info("Plugin '{}' uninstalled by {}", pluginId, currentUser);
		return ResponseEntity.noContent().build();
	}

	// -------------------------------------------------------------------------
	// GET /plugins/{pluginId}/frontend/{filename}
	// -------------------------------------------------------------------------

	@GetMapping("/{pluginId}/frontend/{filename}")
	@Operation(summary = "Serve a plugin frontend asset", description = "Returns a static file from the plugin's frontend bundle " +
		"(e.g. remoteEntry.js, bundle.js). Used by OH UI to load " +
		"the plugin's React components via Module Federation.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "File returned"),
			@ApiResponse(responseCode = "404", description = "File not found", content = @Content),
			@ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content)
	})
	public ResponseEntity<Resource> serveFrontendAsset(
		@Parameter(description = "Plugin identifier", required = true) @PathVariable String pluginId,
		@Parameter(description = "Filename within the frontend bundle", example = "remoteEntry.js") @PathVariable String filename) {

		// Security: reject path traversal attempts
		if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
			return ResponseEntity.badRequest().build();
		}

		Path filePath = Path.of(stagingDir, pluginId, "frontend", filename);

		if (!Files.exists(filePath)) {
			return ResponseEntity.notFound().build();
		}

		String contentType = filename.endsWith(".js") ? "application/javascript"
			: filename.endsWith(".css") ? "text/css" : filename.endsWith(".map") ? "application/json" : "application/octet-stream";

		return ResponseEntity.ok()
			.header("Content-Type", contentType)
			// CORS: OH UI runs on a different port (e.g. :5173) — allow cross-origin load
			.header("Access-Control-Allow-Origin", "*")
			.header("Access-Control-Allow-Methods", "GET, OPTIONS")
			.header("Access-Control-Allow-Headers", "Authorization, Content-Type")
			// Cache: allow browser to cache the bundle for 1 hour
			.header("Cache-Control", "public, max-age=3600")
			.body(new FileSystemResource(filePath));
	}

	// =========================================================================
	// Private helpers
	// =========================================================================

	private ResponseEntity< ? > setStatus(String pluginId,
		PluginStatus requiredStatus,
		PluginStatus newStatus,
		EventType... events) {
		String currentUser = currentUsername();
		OhPlugin plugin = pluginRepository.findById(pluginId).orElse(null);
		if (plugin == null) {
			return ResponseEntity.notFound().build();
		}
		if (plugin.getStatus() != requiredStatus) {
			return ResponseEntity.status(HttpStatus.CONFLICT)
				.body("Plugin '" + pluginId + "' is in status " +
					plugin.getStatus() + " — expected " + requiredStatus);
		}
		if (newStatus == PluginStatus.ACTIVE) {
			try {
				verifyStoredJarManifest(plugin);
			} catch (IllegalArgumentException e) {
				LOGGER.warn("Plugin '{}' activation rejected: {}", pluginId, e.getMessage());
				return ResponseEntity.status(HttpStatus.CONFLICT)
					.body(errorBody("Plugin artifact verification failed: " + e.getMessage()));
			} catch (IOException e) {
				LOGGER.error("Failed to verify plugin '{}' JAR before activation", pluginId, e);
				return ResponseEntity.internalServerError()
					.body(errorBody("Failed to verify plugin artifact: " + e.getMessage()));
			}
		}
		plugin.setStatus(newStatus);
		pluginRepository.save(plugin);

		LocalDateTime now = LocalDateTime.now();
		for (EventType et : events) {
			eventRepository.save(new OhPluginEvent(plugin, et, now, currentUser, null));
		}
		LOGGER.info("Plugin '{}' status changed to {} by {}", pluginId, newStatus, currentUser);
		return ResponseEntity.ok(pluginMapper.toDTO(plugin));
	}

	private String currentUsername() {
		return SecurityContextHolder.getContext().getAuthentication().getName();
	}

	private Map<String, String> errorBody(String message) {
		return Map.of("message", message);
	}

	private void verifyStoredJarManifest(OhPlugin plugin) throws IOException {
		ManifestVerifier.verifyJarManifestMatches(plugin.getManifestJson(), resolveJar(plugin));
	}

	private Path resolveJar(OhPlugin plugin) {
		if (plugin.getJarPath() != null) {
			Path storedPath = Path.of(plugin.getJarPath());
			if (Files.exists(storedPath)) {
				return storedPath;
			}
			LOGGER.warn("Plugin '{}' stored JAR path not found: {} — trying staging dir",
				plugin.getPluginId(), storedPath);
		}

		Path fallback = Path.of(stagingDir, plugin.getPluginId() + ".jar");
		if (Files.exists(fallback)) {
			return fallback;
		}

		throw new IllegalArgumentException(
			"JAR not found for plugin '" + plugin.getPluginId() +
				"'. Expected at: " + fallback);
	}

	/**
	 * Extracts {@code manifest.json} and the plugin JAR from the uploaded ZIP.
	 *
	 * @throws IllegalArgumentException if the ZIP is malformed or missing required entries
	 * @throws IOException if reading fails
	 */
	private ExtractedZip extractZip(MultipartFile file)
		throws IOException {
		byte[] manifestBytes = null;
		byte[] jarBytes = null;
		Map<String, byte[]> frontendFiles = new HashMap<>();

		try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
			ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {
				String name = entry.getName();
				if ("manifest.json".equals(name)) {
					manifestBytes = zis.readAllBytes();
				} else if (name.endsWith(".jar") && !name.contains("/")) {
					jarBytes = zis.readAllBytes();
				} else if (name.startsWith("frontend/") && !entry.isDirectory()) {
					// e.g. "frontend/remoteEntry.js" → key = "remoteEntry.js"
					String relativeName = name.substring("frontend/".length());
					if (!relativeName.isBlank()) {
						frontendFiles.put(relativeName, zis.readAllBytes());
					}
				}
				zis.closeEntry();
			}
		}

		if (manifestBytes == null) {
			throw new IllegalArgumentException(
				"ZIP does not contain manifest.json at root level");
		}
		if (jarBytes == null) {
			throw new IllegalArgumentException(
				"ZIP does not contain a JAR file at root level");
		}

		String json = new String(manifestBytes, StandardCharsets.UTF_8);
		PluginDescriptor descriptor;
		try {
			descriptor = ManifestJsonReader.read(json);
		} catch (Exception e) {
			throw new IllegalArgumentException("Invalid manifest.json: " + e.getMessage(), e);
		}
		ManifestVerifier.verifyJarManifestMatches(json, jarBytes);

		return new ExtractedZip(descriptor, jarBytes, json, frontendFiles);
	}

	private Path saveToStaging(String pluginId, byte[] jarBytes) throws IOException {
		Path stagingPath = Path.of(stagingDir);
		Files.createDirectories(stagingPath);
		Path jarPath = stagingPath.resolve(pluginId + ".jar");
		Files.write(jarPath, jarBytes);
		return jarPath;
	}

	private void saveFrontendToStaging(String pluginId,
		Map<String, byte[]> frontendFiles) throws IOException {
		if (frontendFiles == null || frontendFiles.isEmpty())
			return;
		Path stagingPath = Path.of(stagingDir);
		Path frontendDir = stagingPath.resolve(pluginId).resolve("frontend");
		Files.createDirectories(frontendDir);
		for (Map.Entry<String, byte[]> entry : frontendFiles.entrySet()) {
			Path target = frontendDir.resolve(entry.getKey());
			Files.createDirectories(target.getParent());
			Files.write(target, entry.getValue());
		}
		LOGGER.debug("Plugin '{}' — saved {} frontend file(s) to {}",
			pluginId, frontendFiles.size(), frontendDir);
	}

	private record ExtractedZip(
		PluginDescriptor descriptor,
		byte[] jarBytes,
		String manifestJson,
		Map<String, byte[]> frontendFiles // relative path → bytes, e.g. "remoteEntry.js" → ...
	) {
	}
}
