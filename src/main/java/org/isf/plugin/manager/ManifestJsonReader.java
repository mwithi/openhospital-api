/*
 * Open Hospital (www.open-hospital.org)
 * Copyright © 2006-2024 Informatici Senza Frontiere (info@informaticisenzafrontiere.org)
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
import org.isf.plugin.model.FieldPermission;
import org.isf.plugin.model.PluginCapability;
import org.isf.plugin.model.PluginDescriptor;
import org.isf.plugin.model.PluginPermission;
import org.isf.plugin.model.field.AdmissionField;
import org.isf.plugin.model.field.DomainField;
import org.isf.plugin.model.field.LaboratoryField;
import org.isf.plugin.model.field.PatientField;
import org.isf.plugin.model.field.PharmacyField;
import org.isf.plugin.model.field.WardField;

import java.util.ArrayList;
import java.util.List;

/**
 * Deserializes a {@code manifest.json} string into a {@link PluginDescriptor}.
 *
 * <p>This is the inverse of the {@code DescriptorSerializer} in
 * {@code plugin-maven-plugin}. Used at install time when the administrator
 * uploads a plugin ZIP — the manifest is read from the ZIP before any
 * plugin code is executed.
 *
 * <p>Uses Jackson for parsing; the SPI itself has no JSON dependency.
 */
public final class ManifestJsonReader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ManifestJsonReader() {}

    /**
     * Parses a {@code manifest.json} string and builds a {@link PluginDescriptor}.
     *
     * @param json the full content of {@code manifest.json}
     * @return the parsed descriptor
     * @throws Exception if the JSON is malformed or any required field is missing or invalid
     */
    public static PluginDescriptor read(String json) throws Exception {
        JsonNode root = MAPPER.readTree(json);

        PluginDescriptor.Builder builder = PluginDescriptor.builder()
            .pluginId(requireText(root, "pluginId"))
            .version(requireText(root, "version"))
            .name(requireText(root, "name"))
            .entryPoint(requireText(root, "entryPoint"))
            .minCoreVersion(requireText(root, "minCoreVersion"));

        if (root.hasNonNull("description")) {
            builder.description(root.get("description").asText());
        }
        if (root.hasNonNull("vendor")) {
            builder.vendor(root.get("vendor").asText());
        }
        if (root.hasNonNull("license")) {
            builder.license(root.get("license").asText());
        }
        if (root.hasNonNull("maxCoreVersion")) {
            builder.maxCoreVersion(root.get("maxCoreVersion").asText());
        }

        if (root.has("capabilities")) {
            List<PluginCapability> caps = new ArrayList<>();
            for (JsonNode n : root.get("capabilities")) {
                caps.add(PluginCapability.valueOf(n.asText()));
            }
            builder.capabilities(caps);
        }

        if (root.has("permissions")) {
            List<PluginPermission> perms = new ArrayList<>();
            for (JsonNode n : root.get("permissions")) {
                perms.add(PluginPermission.valueOf(n.asText()));
            }
            builder.permissions(perms);
        }

        if (root.has("fieldPermissions")) {
            List<FieldPermission> fps = new ArrayList<>();
            for (JsonNode fp : root.get("fieldPermissions")) {
                fps.add(parseFieldPermission(fp));
            }
            builder.fieldPermissions(fps);
        }

        return builder.build();
    }

    private static FieldPermission parseFieldPermission(JsonNode fp) {
        String domain  = fp.get("domain").asText();
        String access  = fp.get("access").asText();
        String purpose = fp.get("purpose").asText();

        List<DomainField> fields = new ArrayList<>();
        for (JsonNode fieldNode : fp.get("fields")) {
            fields.add(resolveField(domain, fieldNode.asText()));
        }

        DomainField[] arr = fields.toArray(new DomainField[0]);
        if ("WRITE".equalsIgnoreCase(access)) {
            return FieldPermission.write(castArray(domain, arr)).purpose(purpose);
        } else {
            return FieldPermission.read(castArray(domain, arr)).purpose(purpose);
        }
    }

    private static DomainField resolveField(String domain, String fieldName) {
        if ("Patient".equals(domain))    return findByName(PatientField.values(),    fieldName);
        if ("Admission".equals(domain))  return findByName(AdmissionField.values(),  fieldName);
        if ("Laboratory".equals(domain)) return findByName(LaboratoryField.values(), fieldName);
        if ("Ward".equals(domain))       return findByName(WardField.values(),       fieldName);
        if ("Pharmacy".equals(domain))   return findByName(PharmacyField.values(),   fieldName);
        throw new IllegalArgumentException("Unknown domain: " + domain);
    }

    @SuppressWarnings("unchecked")
    private static <F extends DomainField> F[] castArray(String domain, DomainField[] fields) {
        if ("Patient".equals(domain))    return (F[]) castTo(fields, PatientField.class);
        if ("Admission".equals(domain))  return (F[]) castTo(fields, AdmissionField.class);
        if ("Laboratory".equals(domain)) return (F[]) castTo(fields, LaboratoryField.class);
        if ("Ward".equals(domain))       return (F[]) castTo(fields, WardField.class);
        if ("Pharmacy".equals(domain))   return (F[]) castTo(fields, PharmacyField.class);
        throw new IllegalArgumentException("Unknown domain: " + domain);
    }

    @SuppressWarnings("unchecked")
    private static <F extends DomainField> F[] castTo(DomainField[] fields, Class<F> type) {
        F[] arr = (F[]) java.lang.reflect.Array.newInstance(type, fields.length);
        for (int i = 0; i < fields.length; i++) {
            arr[i] = type.cast(fields[i]);
        }
        return arr;
    }

    private static <F extends DomainField> F findByName(F[] values, String name) {
        for (F f : values) {
            if (f.fieldName().equals(name)) return f;
        }
        throw new IllegalArgumentException("Unknown field '" + name + "'");
    }

    private static String requireText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new IllegalArgumentException(
                "manifest.json: required field '" + field + "' is missing or blank");
        }
        return node.asText();
    }
}
