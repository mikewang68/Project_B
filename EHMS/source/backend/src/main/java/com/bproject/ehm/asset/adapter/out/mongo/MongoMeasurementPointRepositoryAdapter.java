package com.bproject.ehm.asset.adapter.out.mongo;

import com.bproject.ehm.asset.domain.model.MeasurementPoint;
import com.bproject.ehm.asset.ports.MeasurementPointRepository;
import com.bproject.ehm.shared.page.PageQuery;
import com.bproject.ehm.shared.page.PageResult;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Repository
public class MongoMeasurementPointRepositoryAdapter implements MeasurementPointRepository {
    private final MongoMeasurementPointSpringRepository repository;
    private final MongoTemplate template;

    public MongoMeasurementPointRepositoryAdapter(MongoMeasurementPointSpringRepository repository,
                                                  MongoTemplate template) {
        this.repository = repository;
        this.template = template;
    }

    @Override
    public PageResult<MeasurementPoint> findActiveByAssetCode(String assetCode, PageQuery page, String keyword) {
        Criteria criteria = activeCriteria(assetCode);
        if (keyword != null && !keyword.isBlank()) {
            Pattern pattern = Pattern.compile(Pattern.quote(keyword.trim()), Pattern.CASE_INSENSITIVE);
            criteria = new Criteria().andOperator(criteria, new Criteria().orOperator(
                    Criteria.where("_id").regex(pattern), Criteria.where("name").regex(pattern),
                    Criteria.where("metric").regex(pattern)));
        }
        long total = template.count(Query.query(criteria), MeasurementPointDocument.class);
        Query query = Query.query(criteria).with(PageRequest.of(page.page(), page.size(),
                Sort.by(Sort.Direction.ASC, "componentCode").and(Sort.by(Sort.Direction.ASC, "_id"))));
        List<MeasurementPoint> content = template.find(query, MeasurementPointDocument.class).stream()
                .map(MeasurementPointDocument::toDomain).toList();
        return PageResult.of(content, page, total);
    }

    @Override
    public List<MeasurementPoint> findActiveByCodes(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) return List.of();
        Criteria criteria = new Criteria().andOperator(Criteria.where("_id").in(codes),
                Criteria.where("archived").ne(true));
        return template.find(Query.query(criteria), MeasurementPointDocument.class).stream()
                .map(MeasurementPointDocument::toDomain).toList();
    }

    @Override
    public List<String> findActiveCodesByAssetCode(String assetCode) {
        return template.findDistinct(Query.query(activeCriteria(assetCode)), "_id",
                MeasurementPointDocument.class, String.class);
    }

    @Override
    public List<String> findEnabledCodesByAssetCode(String assetCode) {
        Criteria criteria = new Criteria().andOperator(activeCriteria(assetCode), Criteria.where("enabled").is(true));
        return template.findDistinct(Query.query(criteria), "_id", MeasurementPointDocument.class, String.class);
    }

    @Override
    public Optional<MeasurementPoint> findByCode(String code) {
        return repository.findById(code).map(MeasurementPointDocument::toDomain);
    }

    @Override
    public boolean existsByCode(String code) {
        return repository.existsById(code);
    }

    @Override
    public MeasurementPoint save(MeasurementPoint point) {
        return repository.save(MeasurementPointDocument.fromDomain(point)).toDomain();
    }

    @Override
    public long countActiveByAssetCode(String assetCode) {
        return template.count(Query.query(activeCriteria(assetCode)), MeasurementPointDocument.class);
    }

    @Override
    public long countEnabledByAssetCode(String assetCode) {
        return template.count(Query.query(new Criteria().andOperator(activeCriteria(assetCode),
                Criteria.where("enabled").is(true))), MeasurementPointDocument.class);
    }

    @Override
    public long countActiveByComponentCode(String componentCode) {
        Criteria criteria = new Criteria().andOperator(Criteria.where("componentCode").is(componentCode),
                Criteria.where("archived").ne(true));
        return template.count(Query.query(criteria), MeasurementPointDocument.class);
    }

    private Criteria activeCriteria(String assetCode) {
        return new Criteria().andOperator(Criteria.where("assetCode").is(assetCode),
                Criteria.where("archived").ne(true));
    }
}
