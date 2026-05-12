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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PluginExternalConnectionReaderTest {

	@Test
	void shouldReadConnectionKeyFromManifest() throws Exception {
		String manifestJson = """
			{
			  "externalConnections": [
			    {
			      "connectionKey": "orthanc",
			      "host": "orthanc-server",
			      "port": 8042,
			      "protocol": "HTTP",
			      "purpose": "Read studies",
			      "direction": "OUTBOUND"
			    }
			  ]
			}
			""";

		var connections = PluginExternalConnectionReader.read(manifestJson);

		assertThat(connections).hasSize(1);
		assertThat(connections.get(0).connectionKey()).isEqualTo("orthanc");
		assertThat(connections.get(0).approvalKey()).isEqualTo("orthanc");
		assertThat(connections.get(0).baseUrl()).isEqualTo("http://orthanc-server:8042");
		assertThat(connections.get(0).isOutbound()).isTrue();
	}

	@Test
	void shouldRejectConnectionWithoutKey() {
		String manifestJson = """
			{
			  "externalConnections": [
			    {
			      "host": "orthanc-server",
			      "port": 8042,
			      "protocol": "HTTP",
			      "direction": "OUTBOUND"
			    }
			  ]
			}
			""";

		assertThatThrownBy(() -> PluginExternalConnectionReader.read(manifestJson))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("connectionKey is required");
	}

	@Test
	void shouldRejectConnectionKeyWithPathCharacters() {
		String manifestJson = """
			{
			  "externalConnections": [
			    {
			      "connectionKey": "../evil",
			      "host": "orthanc-server",
			      "port": 8042,
			      "protocol": "HTTP",
			      "direction": "OUTBOUND"
			    }
			  ]
			}
			""";

		assertThatThrownBy(() -> PluginExternalConnectionReader.read(manifestJson))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("unsupported characters");
	}

	@Test
	void shouldRejectNonArrayExternalConnections() {
		String manifestJson = """
			{
			  "externalConnections": {
			    "connectionKey": "orthanc",
			    "host": "orthanc-server",
			    "port": 8042,
			    "protocol": "HTTP",
			    "direction": "OUTBOUND"
			  }
			}
			""";

		assertThatThrownBy(() -> PluginExternalConnectionReader.read(manifestJson))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("externalConnections must be an array");
	}

	@Test
	void shouldRejectDuplicateConnectionKeys() {
		String manifestJson = """
			{
			  "externalConnections": [
			    {
			      "connectionKey": "orthanc",
			      "host": "orthanc-a",
			      "port": 8042,
			      "protocol": "HTTP",
			      "direction": "OUTBOUND"
			    },
			    {
			      "connectionKey": "orthanc",
			      "host": "orthanc-b",
			      "port": 8043,
			      "protocol": "HTTP",
			      "direction": "OUTBOUND"
			    }
			  ]
			}
			""";

		assertThatThrownBy(() -> PluginExternalConnectionReader.read(manifestJson))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("duplicate external connection key 'orthanc'");
	}
}
