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

@RestController
@AllArgsConstructor
@Slf4j
@RequestMapping("/sdlc/auto")
public class InputContoller {
    private final SDLCAutoService sdlcAutoService;

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

}
