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

import java.net.URI;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.isf.plugin.manager.ManifestJsonReader;
import org.isf.plugin.manager.PluginExternalConnectionReader;
import org.isf.plugin.manager.PluginExternalConnectionReader.ManifestExternalConnection;
import org.isf.plugin.model.OhPlugin;
import org.isf.plugin.model.OhPluginApproval;
import org.isf.plugin.model.PluginCapability;
import org.isf.plugin.model.PluginDescriptor;
import org.isf.plugin.service.PluginApprovalRepository;
import org.isf.plugin.service.PluginRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Generic reverse-proxy for plugins that declare {@code EXTERNAL_INTEGRATION}.
 *
 * <h3>How it works</h3>
 * <p>
 * A plugin declares its external connections in the manifest:
 * <pre>
 * new ExternalConnection("orthanc-server", 8042, "HTTP",
 * 	"Orthanc DICOM server", Direction.OUTBOUND)
 * </pre>
 *
 * <p>
 * The plugin's React frontend calls:
 * <pre>
 *   GET /plugins/{pluginId}/proxy/orthanc/api/studies?PatientID=42
 * </pre>
 *
 * <p>
 * This controller:
 * <ol>
 * <li>Verifies the plugin is ACTIVE and has {@code EXTERNAL_INTEGRATION}</li>
 * <li>Resolves the target URL from the declared and approved connection key</li>
 * <li>Forwards the request, injecting {@code X-User} and {@code X-Permissions}</li>
 * <li>Returns the upstream response verbatim - binary-safe (DICOM images, PDFs...)</li>
 * </ol>
 *
 * <h3>Security</h3> Add to {@code SecurityConfig}:
 * <pre>
 *   .requestMatchers("/plugins/{@literal *}/proxy/**").hasAuthority("plugins.read")
 * </pre>
 *
 * <h3>Target URL resolution</h3> The proxy resolves {@code connectionKey} against the OUTBOUND
 * ExternalConnection declarations in the manifest: {@code {protocol}://{host}:{port}}.
 *
 * <h3>Replacing hardcoded controllers</h3> {@code OrthancProxyController} can be removed once the Orthanc plugin sets
 * {@code proxy.url=http://orthanc-server:8042} in its config.
 */
@RestController
@Tag(name = "Plugin Proxy", description = "Generic reverse-proxy for external plugin integrations")
@SecurityRequirement(name = "bearerAuth")
public class PluginProxyController {

	private static final Logger LOG = LoggerFactory.getLogger(PluginProxyController.class);

	private static final Set<String> BLOCKED_REQUEST_HEADERS = Set.of(
		"host", "connection", "transfer-encoding", "te", "trailer", "upgrade",
		"authorization", "cookie", "forwarded", "x-forwarded-for",
		"x-forwarded-host", "x-forwarded-port", "x-forwarded-proto",
		"x-forwarded-prefix");
	private static final Set<String> BLOCKED_RESPONSE_HEADERS = Set.of(
		"transfer-encoding", "connection");

	private final PluginRepository pluginRepository;
	private final PluginApprovalRepository approvalRepository;
	private final WebClient webClient;

	public PluginProxyController(PluginRepository pluginRepository,
		PluginApprovalRepository approvalRepository,
		WebClient.Builder webClientBuilder) {
		this.pluginRepository = pluginRepository;
		this.approvalRepository = approvalRepository;
		this.webClient = webClientBuilder.build();
	}

	// -------------------------------------------------------------------------

	@RequestMapping("/plugins/{pluginId}/proxy/{connectionKey}/**")
	@Operation(summary = "Proxy request to the plugin's external system", description = "Forwards the request to the URL configured for the plugin " +
		"by the declared and approved connection key.")
	public ResponseEntity<byte[]> proxy(
		@Parameter(description = "Plugin identifier", required = true) @PathVariable String pluginId,
		@Parameter(description = "Manifest external connection key", required = true) @PathVariable String connectionKey,
		HttpServletRequest request) {

		// Extract sub-path after /plugins/{pluginId}/proxy/
		// AntPathMatcher does not support {*path} - extract manually from request URI
		String requestUri = request.getRequestURI();
		String contextPath = request.getContextPath();
		String fullPath = requestUri.startsWith(contextPath)
			? requestUri.substring(contextPath.length())
			: requestUri;
		String prefix = "/plugins/" + pluginId + "/proxy/" + connectionKey;
		String path = fullPath.startsWith(prefix)
			? fullPath.substring(prefix.length())
			: "";

		// 1. Load plugin
		OhPlugin plugin = pluginRepository.findById(pluginId).orElse(null);
		if (plugin == null) {
			return ResponseEntity.notFound().build();
		}
		if (plugin.getStatus() != OhPlugin.PluginStatus.ACTIVE) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.build();
		}

		// 2. Resolve descriptor
		PluginDescriptor descriptor;
		try {
			descriptor = ManifestJsonReader.read(plugin.getManifestJson());
		} catch (Exception e) {
			LOG.error("Cannot parse manifest for plugin '{}': {}", pluginId, e.getMessage());
			return ResponseEntity.internalServerError().build();
		}
		if (!descriptor.hasCapability(PluginCapability.EXTERNAL_INTEGRATION)) {
			LOG.warn("Plugin '{}' attempted proxy access without EXTERNAL_INTEGRATION capability",
				pluginId);
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}

		// 3. Resolve target base URL
		ManifestExternalConnection connection;
		try {
			connection = resolveConnection(plugin, connectionKey);
		} catch (IllegalArgumentException e) {
			LOG.warn("Plugin proxy [{}] rejected connection '{}': {}",
				pluginId, connectionKey, e.getMessage());
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		} catch (Exception e) {
			LOG.error("Cannot parse external connections for plugin '{}': {}",
				pluginId, e.getMessage());
			return ResponseEntity.internalServerError().build();
		}
		if (!isConnectionApproved(pluginId, connection.approvalKey())) {
			LOG.warn("Plugin proxy [{}] rejected unapproved connection '{}'",
				pluginId, connectionKey);
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		if (!"HTTP".equalsIgnoreCase(connection.protocol())
			&& !"HTTPS".equalsIgnoreCase(connection.protocol())) {
			LOG.warn("Plugin proxy [{}] rejected non-HTTP connection '{}'",
				pluginId, connectionKey);
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		String baseUrl = connection.baseUrl();

		// 4. Build target URI
		String targetUrl = UriComponentsBuilder
			.fromUriString(baseUrl)
			.path(path.startsWith("/") ? path : "/" + path)
			.query(request.getQueryString())
			.build(true)
			.toUriString();

		LOG.debug("Plugin proxy [{}]: {} {} -> {}",
			pluginId, request.getMethod(), path, targetUrl);

		// 5. Forward request
		try {
			HttpHeaders forwardHeaders = buildForwardHeaders(request);

			ResponseEntity<byte[]> upstream = webClient
				.method(HttpMethod.valueOf(request.getMethod()))
				.uri(URI.create(targetUrl))
				.headers(h -> h.addAll(forwardHeaders))
				.exchangeToMono(response -> response.toEntity(byte[].class))
				.block();

			if (upstream == null) {
				return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
			}

			// Strip hop-by-hop headers from response
			HttpHeaders responseHeaders = new HttpHeaders();
			upstream.getHeaders().forEach((name, values) -> {
				if (!BLOCKED_RESPONSE_HEADERS.contains(name.toLowerCase())) {
					responseHeaders.addAll(name, values);
				}
			});

			return ResponseEntity
				.status(upstream.getStatusCode())
				.headers(responseHeaders)
				.body(upstream.getBody());

		} catch (Exception e) {
			LOG.error("Plugin proxy [{}] error: {}", pluginId, e.getMessage());
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
		}
	}

	// -------------------------------------------------------------------------

	/**
	 * Resolves a declared OUTBOUND connection by key.
	 */
	private ManifestExternalConnection resolveConnection(OhPlugin plugin, String connectionKey)
		throws Exception {
		return PluginExternalConnectionReader.read(plugin.getManifestJson()).stream()
			.filter(ManifestExternalConnection::isOutbound)
			.filter(c -> c.connectionKey().equals(connectionKey))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException(
				"connection key is not declared as OUTBOUND"));
	}

	private boolean isConnectionApproved(String pluginId, String connectionKey) {
		return approvalRepository.findByPluginPluginIdOrderByApprovedAtAsc(pluginId).stream()
			.anyMatch(approval ->
				approval.getApprovalType() == OhPluginApproval.ApprovalType.CONNECTION
					&& connectionKey.equals(approval.getItemKey()));
	}

	private HttpHeaders buildForwardHeaders(HttpServletRequest request) {
		HttpHeaders headers = new HttpHeaders();
		Collections.list(request.getHeaderNames()).forEach(name -> {
			if (!BLOCKED_REQUEST_HEADERS.contains(name.toLowerCase())) {
				headers.addAll(name, Collections.list(request.getHeaders(name)));
			}
		});
		// Inject identity headers for upstream audit
		var auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null) {
			headers.set("X-User", auth.getName());
			headers.set("X-Permissions", auth.getAuthorities().stream()
				.map(a -> a.getAuthority())
				.collect(Collectors.joining(",")));
		}
		return headers;
	}
}
