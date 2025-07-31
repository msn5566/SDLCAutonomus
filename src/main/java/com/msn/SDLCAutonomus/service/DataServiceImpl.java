package com.msn.SDLCAutonomus.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class DataServiceImpl implements DataService {

    private final MongoTemplate mongoTemplate;

    @Override
    public void processData() throws Exception {
        // 1. Parse XML files to create corresponding POJOs
        try (InputStream sourceFile = new ClassPathResource("source.xml").getInputStream();
             InputStream targetFile = new ClassPathResource("expected_target.xml").getInputStream()) {

            Object sourcePojo = parseXmlToPojo(sourceFile);
            Object targetPojo = parseXmlToPojo(targetFile);

            // 2. Read the mapping_with_validation.csv file to determine data mappings
            Map<String, String> mapping = readMappingFromCsv("mapping_with_validation.csv");

            // 3. Transform data from the source POJO to the target POJO according to the mapping rules
            Object transformedTargetPojo = transformData(sourcePojo, targetPojo, mapping);

            // 4. Connect to the MongoDB database and check if the specified collection exists. If not, create it.
            String collectionName = "sourceDataCollection";
            if (!mongoTemplate.collectionExists(collectionName)) {
                mongoTemplate.createCollection(collectionName);
            }

            // 5. Create a document in the MongoDB collection using the data from the source POJO.
            ObjectMapper mapper = new ObjectMapper();
            JsonNode sourceJson = mapper.valueToTree(sourcePojo);
            mongoTemplate.insert(sourceJson, collectionName);

            log.info("Data processing completed successfully.");
        } catch (IOException e) {
            log.error("Error processing data: {}", e.getMessage());
            throw e; // Re-throw the exception to be handled by the controller
        }
    }


    private Object parseXmlToPojo(InputStream inputStream) throws Exception {
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        Document doc = dBuilder.parse(inputStream);
        doc.getDocumentElement().normalize();

        // Assuming the root element name can be used as a class name
        String rootElementName = doc.getDocumentElement().getNodeName();

        // Create a simple Map-based POJO
        Map<String, Object> pojo = new HashMap<>();
        NodeList nodeList = doc.getDocumentElement().getChildNodes();
        for (int i = 0; i < nodeList.getLength(); i++) {
            Node node = nodeList.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                Element element = (Element) node;
                pojo.put(element.getTagName(), element.getTextContent());
            }
        }
        return pojo;
    }

    public Map<String, String> readMappingFromCsv(String csvFile) throws IOException {
        Map<String, String> mapping = new HashMap<>();
        try (InputStream inputStream = new ClassPathResource(csvFile).getInputStream()) {
            new java.util.Scanner(inputStream).useDelimiter("\\A").next().lines()
                    .skip(1) // Skip header row
                    .forEach(line -> {
                        String[] parts = line.split(",");
                        if (parts.length == 2) {
                            mapping.put(parts[0].trim(), parts[1].trim());
                        }
                    });
        }
        return mapping;
    }


    private Object transformData(Object sourcePojo, Object targetPojo, Map<String, String> mapping) {
        Map<String, Object> sourceMap = (Map<String, Object>) sourcePojo;
        Map<String, Object> targetMap = new HashMap<>((Map<String, Object>) targetPojo); // Create a copy to avoid modifying the original

        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String sourceField = entry.getKey();
            String targetField = entry.getValue();

            if (sourceMap.containsKey(sourceField)) {
                targetMap.put(targetField, sourceMap.get(sourceField));
            }
        }

        return targetMap;
    }
}