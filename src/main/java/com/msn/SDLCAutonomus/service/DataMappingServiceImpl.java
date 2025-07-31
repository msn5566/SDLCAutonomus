package com.msn.SDLCAutonomus.service;

import com.msn.SDLCAutonomus.model.SourceData;
import com.msn.SDLCAutonomus.model.TargetData;
import com.msn.SDLCAutonomus.repository.SourceDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class DataMappingServiceImpl implements DataMappingService {

    private final SourceDataRepository sourceDataRepository;

    @Override
    public TargetData mapSourceToTarget(SourceData sourceData, String mappingCsvPath) {
        Map<String, String> mapping = loadMappingFromCsv(mappingCsvPath);
        TargetData targetData = new TargetData();

        // Example mapping logic, adjust based on your CSV structure
        if (mapping.containsKey("SourcePath1")) {
            targetData.setTargetField1(sourceData.getField1());
        }
        if (mapping.containsKey("SourcePath2")) {
            targetData.setTargetField2(sourceData.getField2());
        }

        return targetData;
    }

    private Map<String, String> loadMappingFromCsv(String csvFilePath) {
        Map<String, String> mapping = new HashMap<>();
        try (Reader reader = Files.newBufferedReader(Paths.get(csvFilePath))) {
            CSVFormat csvFormat = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .build();

            for (CSVRecord record : csvFormat.parse(reader)) {
                String sourceField = record.get("SourcePath"); // Adjust header name
                String targetField = record.get("TargetPath"); // Adjust header name
                mapping.put(sourceField, targetField);
            }
        } catch (IOException e) {
            log.error("Error reading CSV file: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            log.error("Error reading CSV file header: {}", e.getMessage());
        }
        return mapping;
    }

    @Override
    public List<SourceData> parseSourceXml(String sourceXmlPath) {
        List<SourceData> sourceDataList = new ArrayList<>();
        try {
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(sourceXmlPath);
            doc.getDocumentElement().normalize();

            NodeList nList = doc.getElementsByTagName("element"); // Replace "element" with actual XML element name

            for (int temp = 0; temp < nList.getLength(); temp++) {
                Node nNode = nList.item(temp);
                if (nNode.getNodeType() == Node.ELEMENT_NODE) {
                    Element eElement = (Element) nNode;
                    SourceData sourceData = new SourceData();
                    // Example: Assuming the XML has <field1> and <field2> elements
                    if(eElement.getElementsByTagName("field1").getLength() > 0){
                        sourceData.setField1(eElement.getElementsByTagName("field1").item(0).getTextContent());
                    }
                    if(eElement.getElementsByTagName("field2").getLength() > 0){
                        sourceData.setField2(eElement.getElementsByTagName("field2").item(0).getTextContent());
                    }
                    sourceDataList.add(sourceData);
                }
            }
        } catch (ParserConfigurationException | SAXException | IOException e) {
            log.error("Error parsing XML file: {}", e.getMessage());
        }
        return sourceDataList;
    }

    @Override
    public List<TargetData> parseTargetXml(String targetXmlPath) {
        List<TargetData> targetDataList = new ArrayList<>();
        try {
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(targetXmlPath);
            doc.getDocumentElement().normalize();

            NodeList nList = doc.getElementsByTagName("element"); // Replace "element" with actual XML element name

            for (int temp = 0; temp < nList.getLength(); temp++) {
                Node nNode = nList.item(temp);
                if (nNode.getNodeType() == Node.ELEMENT_NODE) {
                    Element eElement = (Element) nNode;
                    TargetData targetData = new TargetData();
                    // Example: Assuming the XML has <targetField1> and <targetField2> elements
                    if(eElement.getElementsByTagName("targetField1").getLength() > 0){
                        targetData.setTargetField1(eElement.getElementsByTagName("targetField1").item(0).getTextContent());
                    }
                   if(eElement.getElementsByTagName("targetField2").getLength() > 0){
                        targetData.setTargetField2(eElement.getElementsByTagName("targetField2").item(0).getTextContent());
                   }
                    targetDataList.add(targetData);
                }
            }
        } catch (ParserConfigurationException | SAXException | IOException e) {
            log.error("Error parsing XML file: {}", e.getMessage());
        }
        return targetDataList;
    }

    @Override
    public void saveSourceData(SourceData sourceData) {
        sourceDataRepository.save(sourceData);
    }
}