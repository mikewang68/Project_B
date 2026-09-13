package com.bproject.ehm.asset.adapter.out.mongo;

import com.bproject.ehm.asset.domain.model.Component;
import com.bproject.ehm.asset.ports.ComponentRepository;
import com.bproject.ehm.shared.page.PageQuery;
import com.bproject.ehm.shared.page.PageResult;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Repository
public class MongoComponentRepositoryAdapter implements ComponentRepository {
    private final MongoComponentSpringRepository repository;
    private final MongoTemplate template;

    public MongoComponentRepositoryAdapter(MongoComponentSpringRepository repository, MongoTemplate template) {
        this.repository = repository;
        this.template = template;
    }

    @Override
    public PageResult<Component> findActiveByAssetCode(String assetCode, PageQuery page, String keyword) {
        List<Criteria> filters = new ArrayList<>();
        filters.add(Criteria.where("assetCode").is(assetCode));
        filters.add(Criteria.where("archived").ne(true));
        if (keyword != null && !keyword.isBlank()) {
            Pattern pattern = Pattern.compile(Pattern.quote(keyword.trim()), Pattern.CASE_INSENSITIVE);
            filters.add(new Criteria().orOperator(Criteria.where("_id").regex(pattern),
                    Criteria.where("name").regex(pattern), Criteria.where("category").regex(pattern)));
        }
        Criteria criteria = new Criteria().andOperator(filters.toArray(Criteria[]::new));
        long total = template.count(Query.query(criteria), ComponentDocument.class);
        Query query = Query.query(criteria).with(PageRequest.of(page.page(), page.size(),
                Sort.by(Sort.Direction.ASC, "parentCode").and(Sort.by(Sort.Direction.ASC, "_id"))));
        List<Component> content = template.find(query, ComponentDocument.class).stream()
                .map(ComponentDocument::toDomain).toList();
        return PageResult.of(content, page, total);
    }

    @Override
    public Optional<Component> findByCode(String code) {
        return repository.findById(code).map(ComponentDocument::toDomain);
    }

    @Override
    public boolean existsByCode(String code) {
        return repository.existsById(code);
    }

    @Override
    public Component save(Component component) {
        return repository.save(ComponentDocument.fromDomain(component)).toDomain();
    }

    @Override
    public long countActiveByAssetCode(String assetCode) {
        Criteria criteria = new Criteria().andOperator(Criteria.where("assetCode").is(assetCode),
                Criteria.where("archived").ne(true));
        return template.count(Query.query(criteria), ComponentDocument.class);
    }
}
