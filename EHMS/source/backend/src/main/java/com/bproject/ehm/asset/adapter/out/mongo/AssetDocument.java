package com.bproject.ehm.asset.adapter.out.mongo;

import com.bproject.ehm.asset.domain.model.Asset;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("assets")
public class AssetDocument {
    @Id
    private final String code;
    private final String name;
    private final String type;
    private final String area;
    private final String maintenanceDate;
    private final String owner;
    private final boolean archived;
    private final Instant createdAt;
    private final Instant updatedAt;
    @Version
    private final Long version;

    public AssetDocument(String code, String name, String type, String area, String maintenanceDate,
                         String owner, boolean archived, Instant createdAt, Instant updatedAt, Long version) {
        this.code = code;
        this.name = name;
        this.type = type;
        this.area = area;
        this.maintenanceDate = maintenanceDate;
        this.owner = owner;
        this.archived = archived;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    static AssetDocument fromDomain(Asset asset) {
        return new AssetDocument(asset.code(), asset.name(), asset.type(), asset.area(), asset.maintenanceDate(),
                asset.owner(), asset.archived(), asset.createdAt(), asset.updatedAt(), asset.version());
    }

    Asset toDomain() {
        return new Asset(code, name, type, area, maintenanceDate, owner, archived, createdAt, updatedAt, version);
    }
}
