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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;

class ManifestVerifierTest {

    @Test
    void shouldAcceptEquivalentManifestJsonInsideJar() throws Exception {
        String zipManifest = """
            {
              "pluginId": "demo",
              "version": "1.0.0",
              "name": "Demo Plugin"
            }
            """;
        String jarManifest = "{\"name\":\"Demo Plugin\",\"version\":\"1.0.0\",\"pluginId\":\"demo\"}";

        assertThatCode(() -> ManifestVerifier.verifyJarManifestMatches(zipManifest, jarWithManifest(jarManifest)))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectJarManifestMismatch() throws Exception {
        String zipManifest = "{\"pluginId\":\"demo\",\"version\":\"1.0.0\",\"name\":\"Demo Plugin\"}";
        String jarManifest = "{\"pluginId\":\"demo\",\"version\":\"2.0.0\",\"name\":\"Demo Plugin\"}";

        assertThatThrownBy(() -> ManifestVerifier.verifyJarManifestMatches(zipManifest, jarWithManifest(jarManifest)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not match");
    }

    @Test
    void shouldRejectJarWithoutRootManifest() throws Exception {
        String zipManifest = "{\"pluginId\":\"demo\",\"version\":\"1.0.0\",\"name\":\"Demo Plugin\"}";

        assertThatThrownBy(() -> ManifestVerifier.verifyJarManifestMatches(zipManifest, jarWithoutManifest()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("JAR does not contain manifest.json");
    }

    private byte[] jarWithManifest(String manifestJson) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            JarEntry entry = new JarEntry("manifest.json");
            jar.putNextEntry(entry);
            jar.write(manifestJson.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return out.toByteArray();
    }

    private byte[] jarWithoutManifest() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            JarEntry entry = new JarEntry("example.txt");
            jar.putNextEntry(entry);
            jar.write("content".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return out.toByteArray();
    }
}
