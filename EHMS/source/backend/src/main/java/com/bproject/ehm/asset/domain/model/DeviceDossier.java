package com.bproject.ehm.asset.domain.model;

import java.time.Instant;
import java.util.List;

/** Supplemental asset facts. No PLC control, safety permission, or inferred manufacturer parameters. */
public record DeviceDossier(String assetCode, String manufacturer, String model, String serialNumber,
                            String commissionedOn, String processPosition, String externalCode, String notes,
                            MaintenanceBasis maintenanceBasis, List<DeviceDocument> documents,
                            long version, Instant updatedAt) {
    public DeviceDossier { documents=documents==null?List.of():List.copyOf(documents); }
    public static DeviceDossier empty(String code) {
        return new DeviceDossier(code,"","","","","","","",null,List.of(),0,null);
    }
    public record MaintenanceBasis(String sourceDocumentId, String sourceSection, String sourceKind,
                                   String unit, double interval, double reminderLead,
                                   Instant lastCompletedAt, Double lastServiceReading, Double currentReading,
                                   String ownerTeam) {}
    public record DeviceDocument(String id, String title, String kind, String revision, String filename,
                                 String contentType, long size, String sha256, String status, Instant createdAt) {}
}
