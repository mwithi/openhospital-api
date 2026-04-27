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
package org.isf.plugin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * DTO representing an installed plugin returned by GET /api/plugins
 * and GET /api/plugins/{pluginId}.
 */
@Schema(description = "An installed Open Hospital plugin")
public class PluginDTO {

    @Schema(description = "Plugin identifier in reverse-domain notation",
            example = "org.isf.plugin.example.patientaudit")
    private String pluginId;

    @Schema(description = "Plugin version (semver)", example = "1.0.0")
    private String version;

    @Schema(description = "Human-readable plugin name", example = "Patient Audit Log")
    private String name;

    @Schema(description = "Current lifecycle status",
            allowableValues = {"VALIDATING", "ACTIVE", "DISABLED", "FAILED"})
    private String status;

    @Schema(description = "When the plugin was installed")
    private LocalDateTime installedAt;

    @Schema(description = "Username of the administrator who installed the plugin")
    private String installedBy;

    // -------------------------------------------------------------------------
    // Getters and setters
    // -------------------------------------------------------------------------

    public String        getPluginId()    { return pluginId; }
    public String        getVersion()     { return version; }
    public String        getName()        { return name; }
    public String        getStatus()      { return status; }
    public LocalDateTime getInstalledAt() { return installedAt; }
    public String        getInstalledBy() { return installedBy; }

    public void setPluginId(String pluginId)       { this.pluginId    = pluginId; }
    public void setVersion(String version)          { this.version     = version; }
    public void setName(String name)                { this.name        = name; }
    public void setStatus(String status)            { this.status      = status; }
    public void setInstalledAt(LocalDateTime v)     { this.installedAt = v; }
    public void setInstalledBy(String installedBy)  { this.installedBy = installedBy; }
}
