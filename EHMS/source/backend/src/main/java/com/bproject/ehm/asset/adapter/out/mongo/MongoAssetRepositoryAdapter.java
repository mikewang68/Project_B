package com.bproject.ehm.asset.adapter.out.mongo;

import com.bproject.ehm.asset.domain.model.Asset;
import com.bproject.ehm.asset.ports.AssetRepository;
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
public class MongoAssetRepositoryAdapter implements AssetRepository {
    private final MongoAssetSpringRepository repository;
    private final MongoTemplate template;

    public MongoAssetRepositoryAdapter(MongoAssetSpringRepository repository, MongoTemplate template) {
        this.repository = repository;
        this.template = template;
    }

    @Override
    public PageResult<Asset> findActive(PageQuery page, String keyword, String area, String type) {
        List<Criteria> filters = new ArrayList<>();
        filters.add(Criteria.where("archived").ne(true));
        if (keyword != null && !keyword.isBlank()) {
            Pattern pattern = Pattern.compile(Pattern.quote(keyword.trim()), Pattern.CASE_INSENSITIVE);
            filters.add(new Criteria().orOperator(Criteria.where("_id").regex(pattern), Criteria.where("name").regex(pattern)));
        }
        if (area != null && !area.isBlank()) filters.add(Criteria.where("area").is(area.trim()));
        if (type != null && !type.isBlank()) filters.add(Criteria.where("type").is(type.trim()));

        Criteria criteria = new Criteria().andOperator(filters.toArray(Criteria[]::new));
        Query countQuery = Query.query(criteria);
        long total = template.count(countQuery, AssetDocument.class);
        Query pageQuery = Query.query(criteria)
                .with(PageRequest.of(page.page(), page.size(), Sort.by(Sort.Direction.ASC, "_id")));
        List<Asset> content = template.find(pageQuery, AssetDocument.class).stream()
                .map(AssetDocument::toDomain)
                .toList();
        return PageResult.of(content, page, total);
    }

    @Override
    public Optional<Asset> findByCode(String code) {
        return repository.findById(code).map(AssetDocument::toDomain);
    }

    @Override
    public boolean existsByCode(String code) {
        return repository.existsById(code);
    }

    @Override
    public Asset save(Asset asset) {
        return repository.save(AssetDocument.fromDomain(asset)).toDomain();
    }

    @Override
    public long countActive() {
        return template.count(Query.query(Criteria.where("archived").ne(true)), AssetDocument.class);
    }
}
