package com.msn.SDLCAutonomus.controller;

import com.msn.SDLCAutonomus.service.XmlToMongoService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/xml-to-mongo")
@AllArgsConstructor
@Slf4j
public class XmlToMongoController {

    private final XmlToMongoService xmlToMongoService;

    @PostMapping("/map-and-create")
    public ResponseEntity<String> mapXmlToMongo(
            @RequestParam("sourceXml") MultipartFile sourceXml,
            @RequestParam("targetXml") MultipartFile targetXml,
            @RequestParam("mappingCsv") MultipartFile mappingCsv) {
        try {
            xmlToMongoService.mapXmlAndCreateDocument(sourceXml, targetXml, mappingCsv);
            return ResponseEntity.ok("XML data mapped and MongoDB document created successfully.");
        } catch (Exception e) {
            log.error("Error mapping XML data and creating MongoDB document: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error processing XML to MongoDB: " + e.getMessage());
        }
    }
}