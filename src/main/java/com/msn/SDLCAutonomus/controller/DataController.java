package com.msn.SDLCAutonomus.controller;

import com.msn.SDLCAutonomus.service.DataService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/data")
@AllArgsConstructor
@Slf4j
public class DataController {

    private final DataService dataService;

    @PostMapping("/process")
    public ResponseEntity<String> processData() {
        try {
            dataService.processData();
            return ResponseEntity.ok("Data processed successfully.");
        } catch (Exception e) {
            log.error("Error processing data: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error processing data: " + e.getMessage());
        }
    }
}