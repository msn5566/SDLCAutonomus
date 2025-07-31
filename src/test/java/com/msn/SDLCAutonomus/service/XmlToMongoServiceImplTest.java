package com.msn.SDLCAutonomus.service;

import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import java.util.Map;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class XmlToMongoServiceImplTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @InjectMocks
    private XmlToMongoServiceImpl xmlToMongoService;

    @Test
    void mapXmlAndCreateDocument_success() throws Exception {
        MockMultipartFile sourceXmlFile = new MockMultipartFile("sourceXml", "source.xml", MediaType.APPLICATION_XML_VALUE, "<root><field1>value1</field1></root>".getBytes());
        MockMultipartFile targetXmlFile = new MockMultipartFile("targetXml", "target.xml", MediaType.APPLICATION_XML_VALUE, "<root><targetField>expectedValue</targetField></root>".getBytes());
        MockMultipartFile mappingCsvFile = new MockMultipartFile("mappingCsv", "mapping.csv", MediaType.TEXT_PLAIN_VALUE, "source,target\n/root/field1,targetField".getBytes());

        String collectionName = "mapped_data";

        when(mongoTemplate.collectionExists(collectionName)).thenReturn(false);
        when(mongoTemplate.insert(anyMap(), eq(collectionName))).thenReturn(new Document());

        xmlToMongoService.mapXmlAndCreateDocument(sourceXmlFile, targetXmlFile, mappingCsvFile);

        verify(mongoTemplate, times(1)).collectionExists(collectionName);
        verify(mongoTemplate, times(1)).insert(anyMap(), eq(collectionName));
        verify(mongoTemplate, times(1)).createCollection(collectionName);
    }

    @Test
    void mapXmlAndCreateDocument_collectionExists() throws Exception {
        MockMultipartFile sourceXmlFile = new MockMultipartFile("sourceXml", "source.xml", MediaType.APPLICATION_XML_VALUE, "<root><field1>value1</field1></root>".getBytes());
        MockMultipartFile targetXmlFile = new MockMultipartFile("targetXml", "target.xml", MediaType.APPLICATION_XML_VALUE, "<root><targetField>expectedValue</targetField></root>".getBytes());
        MockMultipartFile mappingCsvFile = new MockMultipartFile("mappingCsv", "mapping.csv", MediaType.TEXT_PLAIN_VALUE, "source,target\n/root/field1,targetField".getBytes());

        String collectionName = "mapped_data";

        when(mongoTemplate.collectionExists(collectionName)).thenReturn(true);
        when(mongoTemplate.insert(anyMap(), eq(collectionName))).thenReturn(new Document());

        xmlToMongoService.mapXmlAndCreateDocument(sourceXmlFile, targetXmlFile, mappingCsvFile);

        verify(mongoTemplate, times(1)).collectionExists(collectionName);
        verify(mongoTemplate, never()).createCollection(collectionName);
        verify(mongoTemplate, times(1)).insert(anyMap(), eq(collectionName));
    }

    @Test
    void mapXmlAndCreateDocument_exceptionReadingFiles() throws Exception {
        MockMultipartFile sourceXmlFile = new MockMultipartFile("sourceXml", "source.xml", MediaType.APPLICATION_XML_VALUE, "<root><field1>value1</field1></root>".getBytes());
        MockMultipartFile targetXmlFile = new MockMultipartFile("targetXml", "target.xml", MediaType.APPLICATION_XML_VALUE, "<root><targetField>expectedValue</targetField></root>".getBytes());
        MockMultipartFile mappingCsvFile = new MockMultipartFile("mappingCsv", "mapping.csv", MediaType.TEXT_PLAIN_VALUE, "source,target\n/root/field1,targetField".getBytes());

        String collectionName = "mapped_data";

        when(mongoTemplate.collectionExists(collectionName)).thenReturn(false);
        when(mongoTemplate.insert(anyMap(), eq(collectionName))).thenThrow(new RuntimeException("Failed to read XML"));

        try {
            xmlToMongoService.mapXmlAndCreateDocument(sourceXmlFile, targetXmlFile, mappingCsvFile);
        } catch (RuntimeException e) {
            // Expected exception
        }

        verify(mongoTemplate, times(1)).collectionExists(collectionName);
        verify(mongoTemplate, times(1)).insert(anyMap(), eq(collectionName));
        verify(mongoTemplate, times(1)).createCollection(collectionName);
    }

    private Map<String, Object> anyMap() {
        return any(Map.class);
    }
}