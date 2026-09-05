package com.bproject.ehm.repository;

import com.bproject.ehm.domain.Device;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface DeviceRepository extends MongoRepository<Device, String> {
}
