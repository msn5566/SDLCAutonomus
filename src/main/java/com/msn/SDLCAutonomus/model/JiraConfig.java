package com.msn.SDLCAutonomus.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class JiraConfig {
    private String jiraUrl;
    private String username;
    private String apiToken;
    private String issueKey;


}
