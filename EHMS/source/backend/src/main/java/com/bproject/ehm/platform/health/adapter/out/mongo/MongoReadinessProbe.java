package com.bproject.ehm.platform.health.adapter.out.mongo;

import com.bproject.ehm.platform.health.ports.ReadinessProbe;
import com.mongodb.client.MongoClient;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MongoReadinessProbe implements ReadinessProbe {
    private final MongoClient mongoClient;
    private final String databaseName;

    public MongoReadinessProbe(MongoClient mongoClient,
                               @Value("${spring.data.mongodb.database:ehm}") String databaseName) {
        this.mongoClient = mongoClient;
        this.databaseName = databaseName;
    }

    @Override
    public ProbeResult check() {
        try {
            mongoClient.getDatabase(databaseName).runCommand(new Document("ping", 1));
            return new ProbeResult(true, "mongo-adapter", "UP", null);
        } catch (Exception exception) {
            return new ProbeResult(false, "mongo-adapter", "DOWN", exception.getMessage());
        }
    }
}
