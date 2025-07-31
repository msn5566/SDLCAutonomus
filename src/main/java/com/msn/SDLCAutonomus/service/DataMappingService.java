package com.msn.SDLCAutonomus.service;

import com.msn.SDLCAutonomus.model.SourceData;
import com.msn.SDLCAutonomus.model.TargetData;

import java.util.List;

public interface DataMappingService {
    TargetData mapSourceToTarget(SourceData sourceData, String mappingCsvPath);
    List<SourceData> parseSourceXml(String sourceXmlPath);
    List<TargetData> parseTargetXml(String targetXmlPath);
    void saveSourceData(SourceData sourceData);
}