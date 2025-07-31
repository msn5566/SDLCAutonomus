package com.msn.SDLCAutonomus.service;

import com.msn.SDLCAutonomus.model.SourceData;
import com.msn.SDLCAutonomus.model.TargetData;
import com.msn.SDLCAutonomus.repository.SourceDataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class DataMappingServiceImplTest {

    @Mock
    private SourceDataRepository sourceDataRepository;

    @InjectMocks
    private DataMappingServiceImpl dataMappingService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testMapSourceToTarget() {
        SourceData sourceData = new SourceData();
        sourceData.setField1("sourceValue1");
        sourceData.setField2("sourceValue2");
        String mappingCsvPath = "src/test/resources/mapping_with_validation.csv"; // Ensure this file exists

        TargetData targetData = dataMappingService.mapSourceToTarget(sourceData, mappingCsvPath);

        assertNotNull(targetData);
        assertEquals("sourceValue1", targetData.getTargetField1());
        assertEquals("sourceValue2", targetData.getTargetField2());
    }

    @Test
    void testParseSourceXml() throws IOException {
          // Create a temporary source.xml file with valid XML content
        File tempFile = new File("src/test/resources/source.xml");
        String xmlContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<root>\n" +
                "  <element>\n" +
                "    <field1>value1</field1>\n" +
                "    <field2>value2</field2>\n" +
                "  </element>\n" +
                "</root>";
        Files.write(tempFile.toPath(), xmlContent.getBytes());

        String sourceXmlPath = tempFile.getAbsolutePath();
        List<SourceData> sourceDataList = dataMappingService.parseSourceXml(sourceXmlPath);

        assertNotNull(sourceDataList);
        assertEquals(1, sourceDataList.size()); // Assuming source.xml contains one element
        assertEquals("value1", sourceDataList.get(0).getField1());
        assertEquals("value2", sourceDataList.get(0).getField2());

        // Clean up: Delete the temporary file
        Files.deleteIfExists(tempFile.toPath());
    }

    @Test
    void testParseTargetXml() throws IOException {
         // Create a temporary expected_target.xml file with valid XML content
        File tempFile = new File("src/test/resources/expected_target.xml");
        String xmlContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<root>\n" +
                "  <element>\n" +
                "    <targetField1>targetValue1</targetField1>\n" +
                "    <targetField2>targetValue2</targetField2>\n" +
                "  </element>\n" +
                "</root>";
        Files.write(tempFile.toPath(), xmlContent.getBytes());

        String targetXmlPath = tempFile.getAbsolutePath();
        List<TargetData> targetDataList = dataMappingService.parseTargetXml(targetXmlPath);

        assertNotNull(targetDataList);
        assertEquals(1, targetDataList.size()); // Assuming expected_target.xml contains one element
        assertEquals("targetValue1", targetDataList.get(0).getTargetField1());
        assertEquals("targetValue2", targetDataList.get(0).getTargetField2());

        // Clean up: Delete the temporary file
        Files.deleteIfExists(tempFile.toPath());
    }

    @Test
    void testSaveSourceData() {
        SourceData sourceData = new SourceData();
        sourceData.setField1("testField1");
        sourceData.setField2("testField2");

        dataMappingService.saveSourceData(sourceData);

        verify(sourceDataRepository, times(1)).save(any(SourceData.class));
    }
}