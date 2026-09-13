package com.bproject.ehm.monitoring.adapter.out.mongo;

import com.bproject.ehm.monitoring.domain.model.DeviceSnapshot;
import com.bproject.ehm.monitoring.domain.model.SnapshotMetrics;
import com.bproject.ehm.monitoring.ports.DeviceSnapshotRepository;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public class MongoDeviceSnapshotRepositoryAdapter implements DeviceSnapshotRepository {
    private final MongoDeviceSnapshotSpringRepository repository;
    private final MongoTemplate template;

    public MongoDeviceSnapshotRepositoryAdapter(MongoDeviceSnapshotSpringRepository repository, MongoTemplate template) {
        this.repository = repository;
        this.template = template;
    }

    @Override
    public Optional<DeviceSnapshot> findByDeviceCode(String deviceCode) {
        return repository.findById(deviceCode).map(DeviceSnapshotDocument::toDomain);
    }

    @Override
    public List<DeviceSnapshot> findByDeviceCodes(Collection<String> deviceCodes) {
        if (deviceCodes.isEmpty()) return List.of();
        return repository.findAllById(deviceCodes).stream().map(DeviceSnapshotDocument::toDomain).toList();
    }

    @Override
    public DeviceSnapshot save(DeviceSnapshot snapshot) {
        return repository.save(DeviceSnapshotDocument.fromDomain(snapshot)).toDomain();
    }

    @Override
    public SnapshotMetrics metrics() {
        long online = count(Criteria.where("condition").nin("离线", "停机", "已归档"));
        long assessable = count(Criteria.where("health").ne(null));
        long healthy = count(Criteria.where("health").gte(80));
        long highRisk = count(Criteria.where("riskClass").in("severe", "critical"));
        return new SnapshotMetrics(online, assessable, healthy, highRisk,
                average("health"), average("quality"));
    }

    private long count(Criteria criteria) {
        return template.count(Query.query(criteria), DeviceSnapshotDocument.class);
    }

    private double average(String field) {
        List<Document> rows = template.getCollection("device_snapshots")
                .aggregate(List.of(
                        new Document("$match", new Document(field, new Document("$ne", null))),
                        new Document("$group", new Document("_id", null).append("value", new Document("$avg", "$" + field)))
                )).into(new java.util.ArrayList<>());
        if (rows.isEmpty()) return 0;
        Number value = rows.get(0).get("value", Number.class);
        return value == null ? 0 : value.doubleValue();
    }
}
