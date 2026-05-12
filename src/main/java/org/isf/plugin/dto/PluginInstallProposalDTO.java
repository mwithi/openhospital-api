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

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DTO returned by POST /api/plugins/install after the ZIP has been validated but before the administrator approves the installation.
 *
 * <p>
 * Contains everything the administrator needs to make an informed decision: capabilities, permissions, field permissions with their declared purpose, and
 * external connections. Nothing is installed yet at this point.
 */
@Schema(description = "Installation proposal presented to the administrator for approval")
public class PluginInstallProposalDTO {

	@Schema(description = "Plugin identifier", example = "org.isf.plugin.example.patientaudit")
	private String pluginId;

	@Schema(description = "Plugin version", example = "1.0.0")
	private String version;

	@Schema(description = "Human-readable plugin name", example = "Patient Audit Log")
	private String name;

	@Schema(description = "Plugin description")
	private String description;

	@Schema(description = "Plugin vendor", example = "Informatici Senza Frontiere")
	private String vendor;

	@Schema(description = "Minimum OH core version required", example = "1.15.0")
	private String minCoreVersion;

	@Schema(description = "Declared capabilities", example = "[\"EVENT_LISTENER\", \"LOG_FILE_WRITE\"]")
	private List<String> capabilities;

	@Schema(description = "Declared permissions", example = "[\"READ_PATIENT\"]")
	private List<String> permissions;

	@Schema(description = "Field-level data access declarations with purpose statements")
	private List<FieldPermissionDTO> fieldPermissions;

	@Schema(description = "Declared external network connections")
	private List<ExternalConnectionDTO> externalConnections;

	@Schema(description = "Whether any field permission requires explicit admin approval " +
		"due to SENSITIVE sensitivity level")
	private boolean requiresExplicitApproval;

	@Schema(description = "UI contribution declared by the plugin, if any")
	private UiContributionDTO uiContribution;

	// -------------------------------------------------------------------------
	// Nested DTOs
	// -------------------------------------------------------------------------

	@Schema(description = "A declared field-level permission with its stated purpose")
	public static class FieldPermissionDTO {

		@Schema(description = "Domain name", example = "Patient")
		private String domain;

		@Schema(description = "Access type", allowableValues = { "READ", "WRITE" })
		private String access;

		@Schema(description = "Field names within the domain", example = "[\"firstName\", \"lastName\"]")
		private List<String> fields;

		@Schema(description = "Stated purpose shown to the administrator", example = "Identify patient in audit log entry")
		private String purpose;

		@Schema(description = "Highest sensitivity level among the declared fields", allowableValues = { "ID", "INTERNAL", "PERSONAL", "CLINICAL",
				"SENSITIVE" })
		private String maxSensitivity;

		public String getDomain() {
			return domain;
		}
		public String getAccess() {
			return access;
		}
		public List<String> getFields() {
			return fields;
		}
		public String getPurpose() {
			return purpose;
		}
		public String getMaxSensitivity() {
			return maxSensitivity;
		}

		public void setDomain(String domain) {
			this.domain = domain;
		}
		public void setAccess(String access) {
			this.access = access;
		}
		public void setFields(List<String> fields) {
			this.fields = fields;
		}
		public void setPurpose(String purpose) {
			this.purpose = purpose;
		}
		public void setMaxSensitivity(String v) {
			this.maxSensitivity = v;
		}
	}

	@Schema(description = "A declared external network connection")
	public static class ExternalConnectionDTO {

		@Schema(description = "Target hostname", example = "pacs.hospital.org")
		private String host;

		@Schema(description = "Target port", example = "11112")
		private int port;

		@Schema(description = "Protocol", example = "DICOM")
		private String protocol;

		@Schema(description = "Stated purpose", example = "Send DICOM study to hospital PACS")
		private String purpose;

		@Schema(description = "Direction", allowableValues = { "OUTBOUND", "INBOUND" })
		private String direction;

		public String getHost() {
			return host;
		}
		public int getPort() {
			return port;
		}
		public String getProtocol() {
			return protocol;
		}
		public String getPurpose() {
			return purpose;
		}
		public String getDirection() {
			return direction;
		}

		public void setHost(String host) {
			this.host = host;
		}
		public void setPort(int port) {
			this.port = port;
		}
		public void setProtocol(String protocol) {
			this.protocol = protocol;
		}
		public void setPurpose(String purpose) {
			this.purpose = purpose;
		}
		public void setDirection(String direction) {
			this.direction = direction;
		}
	}

	public static class UiContributionDTO {

		private BundleDescriptorDTO bundle;
		private List<SlotContributionDTO> slots;
		private List<RouteDescriptorDTO> routes;

		public static class BundleDescriptorDTO {

			private String entry;
			private String remoteName;

			public String getEntry() {
				return entry;
			}
			public void setEntry(String entry) {
				this.entry = entry;
			}
			public String getRemoteName() {
				return remoteName;
			}
			public void setRemoteName(String remoteName) {
				this.remoteName = remoteName;
			}

		}

		public static class SlotContributionDTO {

			private String slotId;
			private String mode; // APPEND, PREPEND, REPLACE
			private String exposedModule;

			public String getSlotId() {
				return slotId;
			}
			public void setSlotId(String slotId) {
				this.slotId = slotId;
			}
			public String getMode() {
				return mode;
			}
			public void setMode(String mode) {
				this.mode = mode;
			}
			public String getExposedModule() {
				return exposedModule;
			}
			public void setExposedModule(String exposedModule) {
				this.exposedModule = exposedModule;
			}
		}

		public static class RouteDescriptorDTO {

			private String path;
			private String label;
			private String menuPath;
			private String permission; // nullable

			public String getPath() {
				return path;
			}
			public void setPath(String path) {
				this.path = path;
			}
			public String getLabel() {
				return label;
			}
			public void setLabel(String label) {
				this.label = label;
			}
			public String getMenuPath() {
				return menuPath;
			}
			public void setMenuPath(String menuPath) {
				this.menuPath = menuPath;
			}
			public String getPermission() {
				return permission;
			}
			public void setPermission(String permission) {
				this.permission = permission;
			}
		}

		public BundleDescriptorDTO getBundle() {
			return bundle;
		}
		public void setBundle(BundleDescriptorDTO bundle) {
			this.bundle = bundle;
		}
		public List<SlotContributionDTO> getSlots() {
			return slots;
		}
		public void setSlots(List<SlotContributionDTO> slots) {
			this.slots = slots;
		}
		public List<RouteDescriptorDTO> getRoutes() {
			return routes;
		}
		public void setRoutes(List<RouteDescriptorDTO> routes) {
			this.routes = routes;
		}
	}

	// -------------------------------------------------------------------------
	// Getters and setters
	// -------------------------------------------------------------------------

	public String getPluginId() {
		return pluginId;
	}
	public String getVersion() {
		return version;
	}
	public String getName() {
		return name;
	}
	public String getDescription() {
		return description;
	}
	public String getVendor() {
		return vendor;
	}
	public String getMinCoreVersion() {
		return minCoreVersion;
	}
	public List<String> getCapabilities() {
		return capabilities;
	}
	public List<String> getPermissions() {
		return permissions;
	}
	public List<FieldPermissionDTO> getFieldPermissions() {
		return fieldPermissions;
	}
	public List<ExternalConnectionDTO> getExternalConnections() {
		return externalConnections;
	}
	public boolean isRequiresExplicitApproval() {
		return requiresExplicitApproval;
	}
	public UiContributionDTO getUiContribution() {
		return uiContribution;
	}

	public void setPluginId(String v) {
		this.pluginId = v;
	}
	public void setVersion(String v) {
		this.version = v;
	}
	public void setName(String v) {
		this.name = v;
	}
	public void setDescription(String v) {
		this.description = v;
	}
	public void setVendor(String v) {
		this.vendor = v;
	}
	public void setMinCoreVersion(String v) {
		this.minCoreVersion = v;
	}
	public void setCapabilities(List<String> v) {
		this.capabilities = v;
	}
	public void setPermissions(List<String> v) {
		this.permissions = v;
	}
	public void setFieldPermissions(List<FieldPermissionDTO> v) {
		this.fieldPermissions = v;
	}
	public void setExternalConnections(List<ExternalConnectionDTO> v) {
		this.externalConnections = v;
	}
	public void setRequiresExplicitApproval(boolean v) {
		this.requiresExplicitApproval = v;
	}
	public void setUiContribution(UiContributionDTO uiContribution) {
		this.uiContribution = uiContribution;
	}
}
