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
package org.isf.plugin.manager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads external connection declarations from manifest JSON.
 *
 * <p>
 * This keeps {@code connectionKey} handling in API until the SPI
 * {@code ExternalConnection} record grows an explicit key field.
 */
public final class PluginExternalConnectionReader {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private PluginExternalConnectionReader() {
	}

	public static List<ManifestExternalConnection> read(String manifestJson) throws Exception {
		JsonNode root = MAPPER.readTree(manifestJson);
		JsonNode connectionsNode = root.get("externalConnections");
		if (connectionsNode == null || connectionsNode.isNull()) {
			return List.of();
		}
		if (!connectionsNode.isArray()) {
			throw new IllegalArgumentException("manifest.json: externalConnections must be an array");
		}

		List<ManifestExternalConnection> connections = new ArrayList<>();
		Set<String> keys = new HashSet<>();
		for (JsonNode node : connectionsNode) {
			String key = optionalText(node, "connectionKey");
			if (key == null) {
				key = optionalText(node, "key");
			}
			if (key == null || key.isBlank()) {
				throw new IllegalArgumentException(
					"manifest.json: externalConnections[].connectionKey is required");
			}
			if (!key.matches("[A-Za-z0-9._-]+")) {
				throw new IllegalArgumentException(
					"manifest.json: external connection key '" + key +
						"' contains unsupported characters");
			}
			if (!keys.add(key)) {
				throw new IllegalArgumentException(
					"manifest.json: duplicate external connection key '" + key + "'");
			}

			connections.add(new ManifestExternalConnection(
				key,
				requireText(node, "host"),
				requirePort(node),
				requireText(node, "protocol"),
				optionalText(node, "purpose") != null ? optionalText(node, "purpose") : "",
				requireText(node, "direction")));
		}
		return connections;
	}

	private static String requireText(JsonNode node, String field) {
		String value = optionalText(node, field);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(
				"manifest.json: externalConnections[]." + field + " is required");
		}
		return value;
	}

	private static String optionalText(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? null : value.asText();
	}

	private static int requirePort(JsonNode node) {
		JsonNode value = node.get("port");
		if (value == null || !value.canConvertToInt()) {
			throw new IllegalArgumentException(
				"manifest.json: externalConnections[].port is required");
		}
		int port = value.asInt();
		if (port < 1 || port > 65535) {
			throw new IllegalArgumentException(
				"manifest.json: externalConnections[].port must be between 1 and 65535");
		}
		return port;
	}

	public record ManifestExternalConnection(
		String connectionKey,
		String host,
		int port,
		String protocol,
		String purpose,
		String direction) {

		public boolean isOutbound() {
			return "OUTBOUND".equalsIgnoreCase(direction);
		}

		public String approvalKey() {
			return connectionKey;
		}

		public String baseUrl() {
			String scheme = "HTTPS".equalsIgnoreCase(protocol) ? "https" : "http";
			return scheme + "://" + host + ":" + port;
		}
	}
}
