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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.isf.plugin.model.OhPlugin;
import org.isf.plugin.model.OhPluginApproval;
import org.isf.plugin.service.PluginApprovalRepository;
import org.isf.plugin.service.PluginRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

class PluginProxyControllerTest {

	private static final String PLUGIN_ID = "org.isf.plugin.demo";

	private PluginRepository pluginRepository;
	private PluginApprovalRepository approvalRepository;
	private AtomicReference<ClientRequest> capturedRequest;
	private AtomicInteger upstreamCalls;
	private ClientResponse upstreamResponse;
	private PluginProxyController controller;

	@BeforeEach
	void setUp() {
		pluginRepository = mock(PluginRepository.class);
		approvalRepository = mock(PluginApprovalRepository.class);
		capturedRequest = new AtomicReference<>();
		upstreamCalls = new AtomicInteger();
		upstreamResponse = ClientResponse.create(HttpStatus.OK)
			.header("Content-Type", "application/json")
			.body("{\"ok\":true}")
			.build();

		ExchangeFunction exchange = request -> {
			capturedRequest.set(request);
			upstreamCalls.incrementAndGet();
			return Mono.just(upstreamResponse);
		};
		controller = new PluginProxyController(
			pluginRepository,
			approvalRepository,
			WebClient.builder().exchangeFunction(exchange));

		SecurityContextHolder.getContext().setAuthentication(
			new TestingAuthenticationToken(
				"admin",
				"password",
				List.of(new SimpleGrantedAuthority("plugins.read"))));
	}

	@Test
	void shouldProxyOnlyDeclaredAndApprovedConnectionKey() {
		givenPlugin(activePlugin(manifest("orthanc", "orthanc-server", 8042, "HTTP", "OUTBOUND", true)));
		givenApprovals(connectionApproval("orthanc"));

		ResponseEntity<byte[]> response = controller.proxy(
			PLUGIN_ID,
			"orthanc",
			request("/plugins/" + PLUGIN_ID + "/proxy/orthanc/api/studies", "PatientID=42"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(new String(response.getBody(), StandardCharsets.UTF_8))
			.isEqualTo("{\"ok\":true}");
		assertThat(capturedRequest.get().url().toString())
			.isEqualTo("http://orthanc-server:8042/api/studies?PatientID=42");
	}

	@Test
	void shouldReturnUpstreamStatusBodyAndHeadersAsReceived() {
		upstreamResponse = ClientResponse.create(HttpStatus.CONFLICT)
			.header("X-Upstream", "kept")
			.header("Connection", "close")
			.body("{\"message\":\"duplicate\"}")
			.build();
		givenPlugin(activePlugin(manifest("orthanc", "orthanc-server", 8042, "HTTP", "OUTBOUND", true)));
		givenApprovals(connectionApproval("orthanc"));

		ResponseEntity<byte[]> response = controller.proxy(
			PLUGIN_ID,
			"orthanc",
			request("/plugins/" + PLUGIN_ID + "/proxy/orthanc/api/studies", null));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(new String(response.getBody(), StandardCharsets.UTF_8))
			.isEqualTo("{\"message\":\"duplicate\"}");
		assertThat(response.getHeaders().getFirst("X-Upstream")).isEqualTo("kept");
		assertThat(response.getHeaders()).doesNotContainKey("Connection");
	}

	@Test
	void shouldRejectUndeclaredConnectionKeyWithoutCallingUpstream() {
		givenPlugin(activePlugin(manifest("orthanc", "orthanc-server", 8042, "HTTP", "OUTBOUND", true)));
		givenApprovals(connectionApproval("orthanc"));

		ResponseEntity<byte[]> response = controller.proxy(
			PLUGIN_ID,
			"evil",
			request("/plugins/" + PLUGIN_ID + "/proxy/evil/api/studies", null));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(upstreamCalls).hasValue(0);
	}

	@Test
	void shouldRejectUnapprovedConnectionKeyWithoutCallingUpstream() {
		givenPlugin(activePlugin(manifest("orthanc", "orthanc-server", 8042, "HTTP", "OUTBOUND", true)));
		givenApprovals();

		ResponseEntity<byte[]> response = controller.proxy(
			PLUGIN_ID,
			"orthanc",
			request("/plugins/" + PLUGIN_ID + "/proxy/orthanc/api/studies", null));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(upstreamCalls).hasValue(0);
	}

	@Test
	void shouldRejectInactivePluginWithoutCallingUpstream() {
		givenPlugin(plugin(OhPlugin.PluginStatus.VALIDATING,
			manifest("orthanc", "orthanc-server", 8042, "HTTP", "OUTBOUND", true)));
		givenApprovals(connectionApproval("orthanc"));

		ResponseEntity<byte[]> response = controller.proxy(
			PLUGIN_ID,
			"orthanc",
			request("/plugins/" + PLUGIN_ID + "/proxy/orthanc/api/studies", null));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(upstreamCalls).hasValue(0);
	}

	@Test
	void shouldRejectPluginWithoutExternalIntegrationCapabilityWithoutCallingUpstream() {
		givenPlugin(activePlugin(manifest("orthanc", "orthanc-server", 8042, "HTTP", "OUTBOUND", false)));
		givenApprovals(connectionApproval("orthanc"));

		ResponseEntity<byte[]> response = controller.proxy(
			PLUGIN_ID,
			"orthanc",
			request("/plugins/" + PLUGIN_ID + "/proxy/orthanc/api/studies", null));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(upstreamCalls).hasValue(0);
	}

	@Test
	void shouldRejectInboundOnlyConnectionWithoutCallingUpstream() {
		givenPlugin(activePlugin(manifest("listener", "example.org", 443, "HTTPS", "INBOUND", true)));
		givenApprovals(connectionApproval("listener"));

		ResponseEntity<byte[]> response = controller.proxy(
			PLUGIN_ID,
			"listener",
			request("/plugins/" + PLUGIN_ID + "/proxy/listener/status", null));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(upstreamCalls).hasValue(0);
	}

	@Test
	void shouldRejectNonHttpConnectionWithoutCallingUpstream() {
		givenPlugin(activePlugin(manifest("pacs", "pacs.hospital.org", 11112, "DICOM", "OUTBOUND", true)));
		givenApprovals(connectionApproval("pacs"));

		ResponseEntity<byte[]> response = controller.proxy(
			PLUGIN_ID,
			"pacs",
			request("/plugins/" + PLUGIN_ID + "/proxy/pacs/studies", null));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(upstreamCalls).hasValue(0);
	}

	@Test
	void shouldStripSensitiveAndForwardedHeaders() {
		givenPlugin(activePlugin(manifest("orthanc", "orthanc-server", 8042, "HTTP", "OUTBOUND", true)));
		givenApprovals(connectionApproval("orthanc"));
		MockHttpServletRequest request = request(
			"/plugins/" + PLUGIN_ID + "/proxy/orthanc/api/studies",
			null);
		request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer secret");
		request.addHeader(HttpHeaders.COOKIE, "SESSION=secret");
		request.addHeader("Host", "evil.example");
		request.addHeader("Forwarded", "host=evil.example");
		request.addHeader("X-Forwarded-Host", "evil.example");
		request.addHeader("X-Safe", "visible");

		controller.proxy(PLUGIN_ID, "orthanc", request);

		HttpHeaders headers = capturedRequest.get().headers();
		assertThat(headers).doesNotContainKey(HttpHeaders.AUTHORIZATION);
		assertThat(headers).doesNotContainKey(HttpHeaders.COOKIE);
		assertThat(headers).doesNotContainKey("Host");
		assertThat(headers).doesNotContainKey("Forwarded");
		assertThat(headers).doesNotContainKey("X-Forwarded-Host");
		assertThat(headers.getFirst("X-Safe")).isEqualTo("visible");
		assertThat(headers.getFirst("X-User")).isEqualTo("admin");
		assertThat(headers.getFirst("X-Permissions")).isEqualTo("plugins.read");
	}

	@Test
	void shouldKeepTargetHostDeclaredWhenPathLooksLikeUrl() {
		givenPlugin(activePlugin(manifest("orthanc", "orthanc-server", 8042, "HTTP", "OUTBOUND", true)));
		givenApprovals(connectionApproval("orthanc"));

		controller.proxy(
			PLUGIN_ID,
			"orthanc",
			request("/plugins/" + PLUGIN_ID + "/proxy/orthanc/http://evil.example/admin", null));

		assertThat(capturedRequest.get().url().getHost()).isEqualTo("orthanc-server");
		assertThat(capturedRequest.get().url().getPort()).isEqualTo(8042);
		assertThat(capturedRequest.get().url().toString()).doesNotStartWith("http://evil.example");
	}

	@Test
	void shouldKeepTargetHostDeclaredWhenQueryContainsExternalUrl() {
		givenPlugin(activePlugin(manifest("orthanc", "orthanc-server", 8042, "HTTP", "OUTBOUND", true)));
		givenApprovals(connectionApproval("orthanc"));

		controller.proxy(
			PLUGIN_ID,
			"orthanc",
			request("/plugins/" + PLUGIN_ID + "/proxy/orthanc/api", "url=https://evil.example"));

		assertThat(capturedRequest.get().url().getHost()).isEqualTo("orthanc-server");
		assertThat(capturedRequest.get().url().getQuery()).isEqualTo("url=https://evil.example");
	}

	private void givenPlugin(OhPlugin plugin) {
		when(pluginRepository.findById(PLUGIN_ID)).thenReturn(Optional.of(plugin));
	}

	private void givenApprovals(OhPluginApproval... approvals) {
		when(approvalRepository.findByPluginPluginIdOrderByApprovedAtAsc(PLUGIN_ID))
			.thenReturn(List.of(approvals));
	}

	private static OhPlugin activePlugin(String manifestJson) {
		return plugin(OhPlugin.PluginStatus.ACTIVE, manifestJson);
	}

	private static OhPlugin plugin(OhPlugin.PluginStatus status, String manifestJson) {
		return new OhPlugin(
			PLUGIN_ID,
			"1.0.0",
			"Demo Plugin",
			status,
			LocalDateTime.now(),
			"tester",
			"target/demo.jar",
			manifestJson);
	}

	private static OhPluginApproval connectionApproval(String connectionKey) {
		return new OhPluginApproval(
			activePlugin(manifest("dummy", "dummy", 80, "HTTP", "OUTBOUND", true)),
			OhPluginApproval.ApprovalType.CONNECTION,
			connectionKey,
			"admin",
			LocalDateTime.now(),
			"");
	}

	private static MockHttpServletRequest request(String requestUri, String queryString) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", requestUri);
		request.setRequestURI(requestUri);
		request.setContextPath("");
		if (queryString != null) {
			request.setQueryString(queryString);
		}
		return request;
	}

	private static String manifest(
		String connectionKey,
		String host,
		int port,
		String protocol,
		String direction,
		boolean externalIntegration) {
		return """
			{
			  "pluginId": "org.isf.plugin.demo",
			  "version": "1.0.0",
			  "name": "Demo Plugin",
			  "entryPoint": "org.isf.plugin.demo.DemoPlugin",
			  "minCoreVersion": "1.15.0",
			  "capabilities": [%s],
			  "permissions": [],
			  "dependencies": [],
			  "fieldPermissions": [],
			  "externalConnections": [
			    {
			      "connectionKey": "%s",
			      "host": "%s",
			      "port": %d,
			      "protocol": "%s",
			      "purpose": "Test connection",
			      "direction": "%s"
			    }
			  ]
			}
			""".formatted(
				externalIntegration ? "\"EXTERNAL_INTEGRATION\"" : "\"EVENT_LISTENER\"",
				connectionKey,
				host,
				port,
				protocol,
				direction);
	}
}
