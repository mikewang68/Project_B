package com.bproject.ehm.repository;

import com.bproject.ehm.domain.Alarm;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface AlarmRepository extends MongoRepository<Alarm, String> {
    List<Alarm> findAllByOrderByOccurredAtDesc();
}
