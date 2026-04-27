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
package org.isf.plugin.bridge;

import org.isf.patient.event.PatientCreatedEvent;
import org.isf.plugin.event.OHDomainEvents;
import org.isf.plugin.registry.PluginRegistryImpl;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Translates Spring ApplicationEvents published by OH-core into
 * {@link OHDomainEvents} and fans them out to all running plugins
 * via {@link PluginRegistryImpl#publishEvent}.
 *
 * <h3>How to add a new bridge</h3>
 * <ol>
 *   <li>Add or find the Spring event in the relevant OH-core IoOperation
 *       (follow the pattern of PatientCreatedEvent / PatientMergedEvent).</li>
 *   <li>Add an {@code @EventListener} method here that maps it to the
 *       corresponding {@link OHDomainEvents} inner class.</li>
 *   <li>The plugin system will automatically fan it out to all subscribed plugins.</li>
 * </ol>
 *
 * <h3>Currently bridged</h3>
 * <ul>
 *   <li>{@link PatientCreatedEvent} → {@link OHDomainEvents.PatientCreated}</li>
 * </ul>
 *
 * <h3>TODO — events declared in OHDomainEvents but not yet bridged</h3>
 * Each item below requires:
 * (a) a Spring event record in openhospital-core (like PatientCreatedEvent), and
 * (b) a corresponding @EventListener method in this class.
 *
 * <ul>
 *   <li><b>PatientUpdated</b> — publish after Patient update in PatientIoOperation.
 *       Maps to {@link OHDomainEvents.PatientUpdated}.</li>
 *
 *   <li><b>PatientDeleted</b> — publish after soft-delete in PatientIoOperation.
 *       Maps to {@link OHDomainEvents.PatientDeleted}.</li>
 *
 *   <li><b>PatientAdmitted</b> — publish after Admission save in AdmissionIoOperations.
 *       Maps to {@link OHDomainEvents.PatientAdmitted(admissionId, patientCode, wardCode)}.
 *       wardCode is available on the Admission entity.</li>
 *
 *   <li><b>PatientDischarged</b> — publish after discharge in AdmissionIoOperations.
 *       Maps to {@link OHDomainEvents.PatientDischarged(admissionId, patientCode, dischargeTypeCode)}.</li>
 *
 *   <li><b>LaboratoryExamCreated</b> — publish after lab exam save in LaboratoryIoOperations.
 *       Maps to {@link OHDomainEvents.LaboratoryExamCreated(examId, patientCode, examCode)}.</li>
 *
 *   <li><b>PluginActivated / PluginDeactivated</b> — published internally by
 *       PluginRegistryImpl itself when a plugin starts or stops. No core bridge needed.</li>
 * </ul>
 */
@Component
public class PatientEventBridge {

    private final PluginRegistryImpl pluginRegistry;

    public PatientEventBridge(PluginRegistryImpl pluginRegistry) {
        this.pluginRegistry = pluginRegistry;
    }

    @EventListener
    public void onPatientCreated(PatientCreatedEvent event) {
        pluginRegistry.publishEvent(
            new OHDomainEvents.PatientCreated(event.patient().getCode()));
    }

    // TODO — add @EventListener for PatientUpdatedEvent when the Spring event
    //   exists in core:
    //
    // @EventListener
    // public void onPatientUpdated(PatientUpdatedEvent event) {
    //     pluginRegistry.publishEvent(
    //         new OHDomainEvents.PatientUpdated(event.patient().getCode()));
    // }

    // TODO — add @EventListener for PatientDeletedEvent:
    //
    // @EventListener
    // public void onPatientDeleted(PatientDeletedEvent event) {
    //     pluginRegistry.publishEvent(
    //         new OHDomainEvents.PatientDeleted(event.patientCode()));
    // }

    // TODO — add @EventListener for AdmissionCreatedEvent:
    //
    // @EventListener
    // public void onPatientAdmitted(AdmissionCreatedEvent event) {
    //     pluginRegistry.publishEvent(new OHDomainEvents.PatientAdmitted(
    //         event.admission().getId(),
    //         event.admission().getPatient().getCode(),
    //         event.admission().getWard().getCode()));
    // }

    // TODO — add @EventListener for AdmissionDischargedEvent:
    //
    // @EventListener
    // public void onPatientDischarged(AdmissionDischargedEvent event) {
    //     pluginRegistry.publishEvent(new OHDomainEvents.PatientDischarged(
    //         event.admission().getId(),
    //         event.admission().getPatient().getCode(),
    //         event.admission().getDisType().getCode()));
    // }

    // TODO — add @EventListener for LaboratoryExamCreatedEvent:
    //
    // @EventListener
    // public void onLaboratoryExamCreated(LaboratoryExamCreatedEvent event) {
    //     pluginRegistry.publishEvent(new OHDomainEvents.LaboratoryExamCreated(
    //         event.laboratory().getCode(),
    //         event.laboratory().getPatient().getCode(),
    //         event.laboratory().getExam().getCode()));
    // }
}
