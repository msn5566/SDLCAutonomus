package com.msn.SDLCAutonomus.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

@Service
@AllArgsConstructor
@Slf4j
public class XmlToMongoServiceImpl implements XmlToMongoService {

    private final MongoTemplate mongoTemplate;

    @Override
    public void mapXmlAndCreateDocument(MultipartFile sourceXmlFile, MultipartFile targetXmlFile, MultipartFile mappingCsvFile) {
        try {
            // 1. Read source.xml, expected_target.xml and mapping_with_validation.csv
            JsonNode sourceXml = readXmlToJson(sourceXmlFile);
            JsonNode targetXml = readXmlToJson(targetXmlFile); //Not used but kept for future use and matching req
            Map<String, String> mapping = readCsvToMap(mappingCsvFile);

            // 2. Create a POJO from source.xml and expected_target.xml (Handled implicitly by JsonNode)

            // 3. Map data from source.xml to match the structure of expected_target.xml based on mapping_with_validation.csv
            Map<String, Object> mappedData = mapData(sourceXml, mapping);

            // 4. Connect to MongoDB (Handled by MongoTemplate)

            // 5. If the required collection doesn't exist, create it.
            String collectionName = "mapped_data"; // You might want to get this from a configuration or input
            if (!mongoTemplate.collectionExists(collectionName)) {
                 log.info("Collection '{}' created.", collectionName);
                 mongoTemplate.createCollection(collectionName);
            }

            // 6. Create a document in the MongoDB collection using the mapped data from source.xml.
            mongoTemplate.insert(mappedData, collectionName);
            log.info("Document inserted into collection '{}'.", collectionName);

        } catch (Exception e) {
            log.error("Error processing XML to MongoDB: {}", e.getMessage(), e);
            throw new RuntimeException("Error processing XML to MongoDB", e);
        }
    }

    private JsonNode readXmlToJson(MultipartFile file) throws Exception {
        XmlMapper xmlMapper = new XmlMapper();
        return xmlMapper.readTree(file.getInputStream());
    }

    private Map<String, String> readCsvToMap(MultipartFile file) throws Exception {
        Map<String, String> mapping = new HashMap<>();
        try (InputStreamReader reader = new InputStreamReader(file.getInputStream());
             CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT.withFirstRecordAsHeader().withTrim())) {
            for (CSVRecord csvRecord : csvParser) {
                String sourceField = csvRecord.get("source");
                String targetField = csvRecord.get("target");
                mapping.put(sourceField, targetField);
            }
        }
        return mapping;
    }

    private Map<String, Object> mapData(JsonNode sourceXml, Map<String, String> mapping) {
        Map<String, Object> mappedData = new HashMap<>();
        mapping.forEach((sourceField, targetField) -> {
            JsonNode node = sourceXml.at(sourceField.replace(".", "/"));
            if (!node.isMissingNode()) {
                mappedData.put(targetField, node.asText());
            } else {
                log.warn("Source field '{}' not found in XML.", sourceField);
            }
        });
        return mappedData;
    }
}