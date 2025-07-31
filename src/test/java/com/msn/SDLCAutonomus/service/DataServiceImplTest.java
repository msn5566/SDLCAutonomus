package com.msn.SDLCAutonomus.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.io.IOException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataServiceImplTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @InjectMocks
    private DataServiceImpl dataService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void processData_success() throws Exception {
        when(mongoTemplate.collectionExists(anyString())).thenReturn(true);
        dataService.processData();
        verify(mongoTemplate, times(1)).collectionExists(anyString());
        verify(mongoTemplate, never()).createCollection(anyString());
        verify(mongoTemplate, times(1)).insert(any(), anyString());
    }

    @Test
    void processData_collectionDoesNotExist() throws Exception {
        when(mongoTemplate.collectionExists(anyString())).thenReturn(false);
        dataService.processData();
        verify(mongoTemplate, times(1)).collectionExists(anyString());
        verify(mongoTemplate, times(1)).createCollection(anyString());
        verify(mongoTemplate, times(1)).insert(any(), anyString());
    }

    @Test
    void processData_IOException() throws Exception {
        DataServiceImpl spyDataService = spy(dataService);
        // Simulate an IOException during file reading (e.g., mapping file not found)
        doThrow(new IOException("Simulated IOException")).when(spyDataService).readMappingFromCsv(anyString());

        try {
            spyDataService.processData();
        } catch (IOException e) {
            // Exception is expected, do nothing
        }

        verify(mongoTemplate, never()).collectionExists(anyString());
        verify(mongoTemplate, never()).createCollection(anyString());
        verify(mongoTemplate, never()).insert(any(), anyString());
        verify(spyDataService, times(1)).readMappingFromCsv(anyString());
    }
}