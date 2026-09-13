package com.bproject.ehm.asset.adapter.out.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;

interface MongoAssetSpringRepository extends MongoRepository<AssetDocument, String> {
}
