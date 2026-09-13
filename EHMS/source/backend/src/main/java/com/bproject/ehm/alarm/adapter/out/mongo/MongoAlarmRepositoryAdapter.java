package com.bproject.ehm.alarm.adapter.out.mongo;

import com.bproject.ehm.alarm.application.AlarmMetrics;
import com.bproject.ehm.alarm.domain.model.Alarm;
import com.bproject.ehm.alarm.domain.model.AlarmStatus;
import com.bproject.ehm.alarm.ports.AlarmRepository;
import com.bproject.ehm.shared.page.PageQuery;
import com.bproject.ehm.shared.page.PageResult;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MongoAlarmRepositoryAdapter implements AlarmRepository {
    private final MongoAlarmSpringRepository repository;
    private final MongoTemplate template;

    public MongoAlarmRepositoryAdapter(MongoAlarmSpringRepository repository, MongoTemplate template) {
        this.repository = repository;
        this.template = template;
    }

    @Override
    public PageResult<Alarm> findAll(PageQuery page) {
        long total = repository.count();
        Query query = new Query().with(PageRequest.of(page.page(), page.size(),
                Sort.by(Sort.Direction.DESC, "occurredAt")));
        List<Alarm> content = template.find(query, AlarmDocument.class).stream().map(AlarmDocument::toDomain).toList();
        return PageResult.of(content, page, total);
    }

    @Override
    public Optional<Alarm> findByAlarmNo(String alarmNo) {
        return repository.findById(alarmNo).map(AlarmDocument::toDomain);
    }

    @Override
    public List<Alarm> findOpenByDeviceCode(String deviceCode, int limit) {
        Query query = Query.query(new Criteria().andOperator(
                        Criteria.where("deviceCode").is(deviceCode),
                        Criteria.where("status").nin(AlarmStatus.CLOSED.name(), AlarmStatus.INVALID.name(), AlarmStatus.SUPPRESSED.name())))
                .limit(limit)
                .with(Sort.by(Sort.Direction.DESC, "occurredAt"));
        return template.find(query, AlarmDocument.class).stream().map(AlarmDocument::toDomain).toList();
    }

    @Override
    public Alarm save(Alarm alarm) {
        return repository.save(AlarmDocument.fromDomain(alarm)).toDomain();
    }

    @Override
    public long countAll() {
        return repository.count();
    }

    @Override
    public AlarmMetrics metrics() {
        Criteria open = Criteria.where("status").nin(AlarmStatus.CLOSED.name(), AlarmStatus.INVALID.name(), AlarmStatus.SUPPRESSED.name());
        long openCount = template.count(Query.query(open), AlarmDocument.class);
        long critical = template.count(Query.query(new Criteria().andOperator(open,
                Criteria.where("levelClass").in("severe", "critical"))), AlarmDocument.class);
        return new AlarmMetrics(openCount, critical);
    }
}
