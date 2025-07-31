package com.msn.SDLCAutonomus.controller;

import com.msn.SDLCAutonomus.service.XmlToMongoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class XmlToMongoControllerTest {

    @Mock
    private XmlToMongoService xmlToMongoService;

    @InjectMocks
    private XmlToMongoController xmlToMongoController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(xmlToMongoController).build();
    }

    @Test
    void mapXmlToMongo_success() throws Exception {
        MockMultipartFile sourceXml = new MockMultipartFile("sourceXml", "source.xml", MediaType.APPLICATION_XML_VALUE, "<root><element>value</element></root>".getBytes());
        MockMultipartFile targetXml = new MockMultipartFile("targetXml", "target.xml", MediaType.APPLICATION_XML_VALUE, "<root><element>expectedValue</element></root>".getBytes());
        MockMultipartFile mappingCsv = new MockMultipartFile("mappingCsv", "mapping.csv", MediaType.TEXT_PLAIN_VALUE, "source,target\n/root/element,element".getBytes());

        doNothing().when(xmlToMongoService).mapXmlAndCreateDocument(sourceXml, targetXml, mappingCsv);

        mockMvc.perform(multipart("/xml-to-mongo/map-and-create")
                        .file(sourceXml)
                        .file(targetXml)
                        .file(mappingCsv))
                .andExpect(status().isOk());

        verify(xmlToMongoService, times(1)).mapXmlAndCreateDocument(sourceXml, targetXml, mappingCsv);
    }

    @Test
    void mapXmlToMongo_serviceThrowsException() throws Exception {
        MockMultipartFile sourceXml = new MockMultipartFile("sourceXml", "source.xml", MediaType.APPLICATION_XML_VALUE, "<root><element>value</element></root>".getBytes());
        MockMultipartFile targetXml = new MockMultipartFile("targetXml", "target.xml", MediaType.APPLICATION_XML_VALUE, "<root><element>expectedValue</element></root>".getBytes());
        MockMultipartFile mappingCsv = new MockMultipartFile("mappingCsv", "mapping.csv", MediaType.TEXT_PLAIN_VALUE, "source,target\n/root/element,element".getBytes());

        doThrow(new RuntimeException("Test exception")).when(xmlToMongoService).mapXmlAndCreateDocument(any(), any(), any());

        mockMvc.perform(multipart("/xml-to-mongo/map-and-create")
                        .file(sourceXml)
                        .file(targetXml)
                        .file(mappingCsv))
                .andExpect(status().isInternalServerError());

        verify(xmlToMongoService, times(1)).mapXmlAndCreateDocument(sourceXml, targetXml, mappingCsv);
    }
}