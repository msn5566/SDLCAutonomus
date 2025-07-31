package com.msn.SDLCAutonomus.repository;

import com.msn.SDLCAutonomus.model.SourceData;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SourceDataRepository extends MongoRepository<SourceData, String> {
}