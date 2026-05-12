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
package org.isf.plugin.mapper;

import java.util.List;

import org.isf.plugin.dto.PluginDTO;
import org.isf.plugin.dto.PluginInstallProposalDTO;
import org.isf.plugin.dto.PluginInstallProposalDTO.ExternalConnectionDTO;
import org.isf.plugin.dto.PluginInstallProposalDTO.FieldPermissionDTO;
import org.isf.plugin.dto.PluginInstallProposalDTO.UiContributionDTO;
import org.isf.plugin.manager.PluginExternalConnectionReader;
import org.isf.plugin.manager.PluginExternalConnectionReader.ManifestExternalConnection;
import org.isf.plugin.model.FieldPermission;
import org.isf.plugin.model.OhPlugin;
import org.isf.plugin.model.PluginDescriptor;
import org.isf.plugin.model.PluginDescriptor.ExternalConnection;
import org.isf.plugin.model.field.FieldSensitivity;
import org.isf.plugin.model.ui.UiContribution;
import org.springframework.stereotype.Component;

/**
 * Maps between plugin model classes and DTOs.
 *
 * <p>
 * Written as a plain Spring {@code @Component} rather than a MapStruct interface because the mappings involve types from the SPI module
 * ({@link PluginDescriptor}, {@link FieldPermission}) that are not JPA entities and require manual conversion logic.
 */
@Component
public class PluginMapper {

	public PluginDTO toDTO(OhPlugin plugin) {
		if (plugin == null)
			return null;
		PluginDTO dto = new PluginDTO();
		dto.setPluginId(plugin.getPluginId());
		dto.setVersion(plugin.getVersion());
		dto.setName(plugin.getName());
		dto.setStatus(plugin.getStatus() != null ? plugin.getStatus().name() : null);
		dto.setInstalledAt(plugin.getInstalledAt());
		dto.setInstalledBy(plugin.getInstalledBy());
		// Include uiContribution so pluginLoader.ts can read exposedModule per slot
		if (plugin.getManifestJson() != null) {
			try {
				org.isf.plugin.model.PluginDescriptor descriptor = org.isf.plugin.manager.ManifestJsonReader.read(plugin.getManifestJson());
				if (descriptor.getUiContribution() != null) {
					dto.setUiContribution(toUiContributionDTO(descriptor.getUiContribution()));
				}
			} catch (Exception e) {
				// Non-fatal: uiContribution will be null, loader falls back to defaults
			}
		}
		return dto;
	}

	public List<PluginDTO> toDTOList(List<OhPlugin> plugins) {
		return plugins.stream().map(this::toDTO).toList();
	}

	public PluginInstallProposalDTO toProposalDTO(PluginDescriptor descriptor) {
		return toProposalDTO(descriptor, null);
	}

	public PluginInstallProposalDTO toProposalDTO(PluginDescriptor descriptor, String manifestJson) {
		PluginInstallProposalDTO dto = new PluginInstallProposalDTO();
		dto.setPluginId(descriptor.getPluginId());
		dto.setVersion(descriptor.getVersion());
		dto.setName(descriptor.getName());
		dto.setDescription(descriptor.getDescription());
		dto.setVendor(descriptor.getVendor());
		dto.setMinCoreVersion(descriptor.getMinCoreVersion());
		dto.setCapabilities(
			descriptor.getCapabilities().stream()
				.map(Enum::name)
				.toList());
		dto.setPermissions(
			descriptor.getPermissions().stream()
				.map(Enum::name)
				.toList());
		dto.setFieldPermissions(
			descriptor.getFieldPermissions().stream()
				.map(this::toFieldPermissionDTO)
				.toList());
		dto.setExternalConnections(toExternalConnectionDTOs(descriptor, manifestJson));
		dto.setRequiresExplicitApproval(descriptor.requiresExplicitApproval());
		if (descriptor.getUiContribution() != null) {
			dto.setUiContribution(toUiContributionDTO(descriptor.getUiContribution()));
		}
		return dto;
	}

	private List<ExternalConnectionDTO> toExternalConnectionDTOs(
		PluginDescriptor descriptor,
		String manifestJson) {
		if (manifestJson != null) {
			try {
				return PluginExternalConnectionReader.read(manifestJson).stream()
					.map(this::toExternalConnectionDTO)
					.toList();
			} catch (Exception e) {
				// Fall back to the SPI descriptor representation for old/invalid stored data.
			}
		}
		return descriptor.getExternalConnections().stream()
			.map(this::toExternalConnectionDTO)
			.toList();
	}

	private FieldPermissionDTO toFieldPermissionDTO(FieldPermission fp) {
		FieldPermissionDTO dto = new FieldPermissionDTO();
		dto.setDomain(fp.getDomainName());
		dto.setAccess(fp.getAccess().name());
		dto.setFields(fp.fieldNames());
		dto.setPurpose(fp.getPurpose());
		dto.setMaxSensitivity(fp.getFields().stream()
			.map(f -> f.sensitivity())
			.max(java.util.Comparator.comparingInt(Enum::ordinal))
			.map(FieldSensitivity::name)
			.orElse(FieldSensitivity.PERSONAL.name()));
		return dto;
	}

	private ExternalConnectionDTO toExternalConnectionDTO(ExternalConnection conn) {
		ExternalConnectionDTO dto = new ExternalConnectionDTO();
		dto.setHost(conn.host());
		dto.setPort(conn.port());
		dto.setProtocol(conn.protocol());
		dto.setPurpose(conn.purpose());
		dto.setDirection(conn.direction().name());
		return dto;
	}

	private ExternalConnectionDTO toExternalConnectionDTO(ManifestExternalConnection conn) {
		ExternalConnectionDTO dto = new ExternalConnectionDTO();
		dto.setConnectionKey(conn.connectionKey());
		dto.setHost(conn.host());
		dto.setPort(conn.port());
		dto.setProtocol(conn.protocol());
		dto.setPurpose(conn.purpose());
		dto.setDirection(conn.direction());
		return dto;
	}

	private UiContributionDTO toUiContributionDTO(UiContribution ui) {
		UiContributionDTO dto = new UiContributionDTO();
		if (ui.getBundle() != null) {
			UiContributionDTO.BundleDescriptorDTO b = new UiContributionDTO.BundleDescriptorDTO();
			b.setEntry(ui.getBundle().entry());
			b.setRemoteName(ui.getBundle().remoteName());
			dto.setBundle(b);
		}
		dto.setSlots(ui.getSlots().stream().map(s -> {
			UiContributionDTO.SlotContributionDTO sd = new UiContributionDTO.SlotContributionDTO();
			sd.setSlotId(s.slotId());
			sd.setExposedModule(s.exposedModule());
			sd.setMode(s.mode().name());
			return sd;
		}).toList());
		dto.setRoutes(ui.getRoutes().stream().map(r -> {
			UiContributionDTO.RouteDescriptorDTO rd = new UiContributionDTO.RouteDescriptorDTO();
			rd.setPath(r.path());
			rd.setLabel(r.label());
			rd.setMenuPath(r.menuPath());
			rd.setPermission(r.permission() != null ? r.permission().name() : null);
			return rd;
		}).toList());
		return dto;
	}
}
