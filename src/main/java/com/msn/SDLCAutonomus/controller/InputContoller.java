package com.msn.SDLCAutonomus.controller;

import com.msn.SDLCAutonomus.service.SDLCAutoService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@AllArgsConstructor
@RequestMapping("/sdlc/auto/{jiraTicket}")
public class InputContoller {
    private final SDLCAutoService sdlcAutoService;

    @GetMapping
    public String takeJiraTicket(@PathVariable String jiraTicket) throws Exception {
        System.out.println("Jira Ticket : "+jiraTicket);
        return sdlcAutoService.runSDLCAuto(jiraTicket);
    }
}
