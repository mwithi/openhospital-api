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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Verifies that the manifest reviewed at install time matches the manifest
 * packaged inside the plugin JAR.
 */
public final class ManifestVerifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MANIFEST_ENTRY = "manifest.json";

    private ManifestVerifier() {}

    public static void verifyJarManifestMatches(String expectedManifestJson, byte[] jarBytes)
        throws IOException {
        try (InputStream inputStream = new ByteArrayInputStream(jarBytes)) {
            verifyJarManifestMatches(expectedManifestJson, inputStream);
        }
    }

    public static void verifyJarManifestMatches(String expectedManifestJson, Path jarPath)
        throws IOException {
        try (InputStream inputStream = Files.newInputStream(jarPath)) {
            verifyJarManifestMatches(expectedManifestJson, inputStream);
        }
    }

    private static void verifyJarManifestMatches(String expectedManifestJson, InputStream jarInputStream)
        throws IOException {
        String jarManifestJson = readRootManifest(jarInputStream);
        if (jarManifestJson == null) {
            throw new IllegalArgumentException("JAR does not contain manifest.json at root level");
        }

        JsonNode expected = parseManifest(expectedManifestJson, "stored manifest.json");
        JsonNode actual = parseManifest(jarManifestJson, "JAR manifest.json");
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                "JAR manifest.json does not match the manifest.json reviewed from the ZIP");
        }
    }

    private static String readRootManifest(InputStream jarInputStream) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(jarInputStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (MANIFEST_ENTRY.equals(entry.getName())) {
                    return new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                }
                zis.closeEntry();
            }
        }
        return null;
    }

    private static JsonNode parseManifest(String json, String source) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid " + source + ": " + e.getMessage(), e);
        }
    }
}
