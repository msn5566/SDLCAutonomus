package com.msn.SDLCAutonomus.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import org.springframework.stereotype.Service;

import com.msn.SDLCAutonomus.model.JiraConfig;

import lombok.extern.slf4j.Slf4j;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.util.Base64;
import org.json.JSONObject;

@Service
@Slf4j
public class ConfigService {


    public JiraConfig getJiraConfig(String issueTicket) throws IOException {
        String url = System.getenv("JIRA_URL");
        String email = System.getenv("JIRA_EMAIL");
        String token = System.getenv("JIRA_API_TOKEN");

        List<String> missingVars = new ArrayList<>();
        if (url == null || url.isBlank()) missingVars.add("JIRA_URL");
        if (email == null || email.isBlank()) missingVars.add("JIRA_EMAIL");
        if (token == null || token.isBlank()) missingVars.add("JIRA_API_TOKEN");

        if (!missingVars.isEmpty()) {
            throw new IOException("Missing required environment variables: " + String.join(", ", missingVars));
        }

        /*String issueTicket;
        try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            log.info("Enter the Jira Issue Key (e.g., PROJ-123):");
            issueTicket = scanner.nextLine().trim();
        }*/
        return new JiraConfig(url, email, token, issueTicket);
    }


    public String getJiraIssueContent(JiraConfig jiraConfig) throws Exception {
        log.info("Connecting to Jira to fetch issue: {}", jiraConfig.getIssueKey());

        HttpClient client = HttpClient.newHttpClient();
        String url = jiraConfig.getJiraUrl() + "/rest/api/2/issue/" + jiraConfig.getIssueKey();

        String auth = jiraConfig.getUsername() + ":" + jiraConfig.getApiToken();
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
            .uri(new URI(url))
            .header("Authorization", "Basic " + encodedAuth)
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch Jira issue. Status code: " + response.statusCode() + " - " + response.body());
        }

        JSONObject issueJson = new JSONObject(response.body());
        JSONObject fields = issueJson.getJSONObject("fields");

        String summary = "Feature: " + fields.getString("summary");
        String description = fields.optString("description", "");

        log.info("✅ Successfully fetched Jira issue: {}", jiraConfig.getIssueKey());
        log.debug("  - Summary: {}", summary);
        log.debug("  - Description: {}", description);

        return summary + "\n\n" + description;
    }

}
