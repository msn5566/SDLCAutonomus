package com.msn.SDLCAutonomus.controller;

import com.msn.SDLCAutonomus.service.DataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataControllerTest {

    @Mock
    private DataService dataService;

    @InjectMocks
    private DataController dataController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        mockMvc = MockMvcBuilders.standaloneSetup(dataController).build();
    }

    @Test
    void processData_success() throws Exception {
        doNothing().when(dataService).processData();
        ResponseEntity<String> response = dataController.processData();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Data processed successfully.", response.getBody());
    }

    @Test
    void processData_failure() throws Exception {
        doThrow(new RuntimeException("Test exception")).when(dataService).processData();
        ResponseEntity<String> response = dataController.processData();
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("Error processing data: Test exception", response.getBody());
    }
}