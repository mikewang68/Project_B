package com.bproject.ehm.asset.adapter.out.mongo;

import com.bproject.ehm.asset.domain.model.Component;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("asset_components")
@CompoundIndex(name = "idx_component_asset_active", def = "{'assetCode': 1, 'archived': 1}")
public class ComponentDocument {
    @Id private final String code;
    private final String assetCode;
    private final String parentCode;
    private final String name;
    private final String category;
    private final String manufacturer;
    private final String model;
    private final String serialNumber;
    private final String criticality;
    private final String position;
    private final String installedOn;
    private final String status;
    private final boolean archived;
    private final Instant createdAt;
    private final Instant updatedAt;
    @Version private final Long version;

    public ComponentDocument(String code, String assetCode, String parentCode, String name, String category,
                             String manufacturer, String model, String serialNumber, String criticality,
                             String position, String installedOn, String status, boolean archived,
                             Instant createdAt, Instant updatedAt, Long version) {
        this.code = code;
        this.assetCode = assetCode;
        this.parentCode = parentCode;
        this.name = name;
        this.category = category;
        this.manufacturer = manufacturer;
        this.model = model;
        this.serialNumber = serialNumber;
        this.criticality = criticality;
        this.position = position;
        this.installedOn = installedOn;
        this.status = status;
        this.archived = archived;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    static ComponentDocument fromDomain(Component component) {
        return new ComponentDocument(component.code(), component.assetCode(), component.parentCode(), component.name(),
                component.category(), component.manufacturer(), component.model(), component.serialNumber(),
                component.criticality(), component.position(), component.installedOn(), component.status(),
                component.archived(), component.createdAt(), component.updatedAt(), component.version());
    }

    Component toDomain() {
        return new Component(code, assetCode, parentCode, name, category, manufacturer, model, serialNumber,
                criticality, position, installedOn, status, archived, createdAt, updatedAt, version);
    }
}
