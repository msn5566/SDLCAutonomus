package com.msn.SDLCAutonomus.controller;

import com.msn.SDLCAutonomus.service.SDLCAutoService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.msn.SDLCAutonomus.service.DataMappingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;

@RestController
@AllArgsConstructor
@Slf4j
@RequestMapping("/sdlc/auto")
public class InputContoller {

    private final SDLCAutoService sdlcAutoService;
    private final DataMappingService dataMappingService;

    @GetMapping("/code/{jiraTicket}")
    public String takeJiraTicket(@PathVariable String jiraTicket) throws Exception {
        log.info("Jira Ticket : {}", jiraTicket);
        return sdlcAutoService.runSDLCAuto(jiraTicket);
    }

    @PostMapping("/xmltopojo")
    public String convertXMLtToPOJO(@RequestParam("agent") String agent,@RequestParam("input1") String input1,@RequestBody String input2) throws Exception {
        //System.out.println("Jira Ticket : "+xmldataString);
        return sdlcAutoService.runDynamicAgent(agent,input1,input2);
    }

    @GetMapping("/xml-transform/{jiraTicket}")
    public String runXmlTransformationFromJiraAttachments(
            @PathVariable String jiraTicket,
            @RequestParam("xmlAttachment") String xmlAttachmentName,
            @RequestParam("excelAttachment") String excelAttachmentName) throws Exception {
        log.info("XML Transformation Request - Jira Ticket: {}, XML: {}, Excel: {}", 
                jiraTicket, xmlAttachmentName, excelAttachmentName);
        return sdlcAutoService.runXmlTransformationWorkflowFromJiraAttachments(jiraTicket, xmlAttachmentName, excelAttachmentName);
    }

    @GetMapping("/attachments/{jiraTicket}")
    public String listJiraAttachments(@PathVariable String jiraTicket) throws Exception {
        log.info("Listing attachments for Jira ticket: {}", jiraTicket);
        return sdlcAutoService.listJiraAttachments(jiraTicket);
    }

    @PostMapping("/transform-and-store")
    public ResponseEntity<String> transformAndStore(
            @RequestParam("sourceXml") MultipartFile sourceXml,
            @RequestParam("targetXml") MultipartFile targetXml,
            @RequestParam("mappingCsv") MultipartFile mappingCsv) {

        try {
            // Save uploaded files to temporary locations
            File sourceXmlFile = new File("source.xml");
            sourceXml.transferTo(sourceXmlFile);

            File targetXmlFile = new File("expected_target.xml");
            targetXml.transferTo(targetXmlFile);

            File mappingCsvFile = new File("mapping_with_validation.csv");
            mappingCsv.transferTo(mappingCsvFile);


            // 1. Parse XML files
            var sourceDataList = dataMappingService.parseSourceXml(sourceXmlFile.getAbsolutePath());
            var targetDataList = dataMappingService.parseTargetXml(targetXmlFile.getAbsolutePath());

            // 2. Map data and save to MongoDB (example: first element only)
            if (!sourceDataList.isEmpty()) {
                var sourceData = sourceDataList.get(0);
                var targetData = dataMappingService.mapSourceToTarget(sourceData, mappingCsvFile.getAbsolutePath());
                dataMappingService.saveSourceData(sourceData); // Save the source data

                return ResponseEntity.ok("Data transformation and storage completed successfully.");
            } else {
                return ResponseEntity.badRequest().body("No data found in source XML.");
            }
        } catch (IOException e) {
            log.error("Error processing files: {}", e.getMessage());
            return ResponseEntity.internalServerError().body("Error processing files.");
        }
    }
}