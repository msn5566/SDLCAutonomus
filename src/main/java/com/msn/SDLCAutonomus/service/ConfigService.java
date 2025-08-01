package com.msn.SDLCAutonomus.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.msn.SDLCAutonomus.model.JiraConfig;
import com.msn.SDLCAutonomus.model.JiraAttachment;

import lombok.extern.slf4j.Slf4j;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.util.Base64;
import org.json.JSONObject;
import org.json.JSONArray;

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

    /**
     * Fetches attachment metadata for a Jira issue
     */
    public List<JiraAttachment> getJiraAttachments(JiraConfig jiraConfig) throws Exception {
        log.info("Fetching attachments for Jira issue: {}", jiraConfig.getIssueKey());

        HttpClient client = HttpClient.newHttpClient();
        String url = jiraConfig.getJiraUrl() + "/rest/api/2/issue/" + jiraConfig.getIssueKey() + "?fields=attachment";

        String auth = jiraConfig.getUsername() + ":" + jiraConfig.getApiToken();
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
            .uri(new URI(url))
            .header("Authorization", "Basic " + encodedAuth)
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch Jira attachments. Status code: " + response.statusCode() + " - " + response.body());
        }

        JSONObject issueJson = new JSONObject(response.body());
        JSONObject fields = issueJson.getJSONObject("fields");
        JSONArray attachments = fields.optJSONArray("attachment");

        List<JiraAttachment> attachmentList = new ArrayList<>();
        if (attachments != null) {
            for (int i = 0; i < attachments.length(); i++) {
                JSONObject attachment = attachments.getJSONObject(i);
                JiraAttachment jiraAttachment = new JiraAttachment(
                    attachment.getString("filename"),
                    attachment.getString("content"),
                    attachment.getLong("size"),
                    attachment.getString("mimeType")
                );
                attachmentList.add(jiraAttachment);
            }
        }

        log.info("✅ Successfully fetched {} attachments for issue: {}", attachmentList.size(), jiraConfig.getIssueKey());
        return attachmentList;
    }

    /**
     * Downloads a specific attachment from Jira
     */
    public byte[] downloadJiraAttachment(JiraConfig jiraConfig, String attachmentUrl) throws Exception {
        log.info("Downloading attachment from: {}", attachmentUrl);

        HttpClient client = HttpClient.newHttpClient();
        String auth = jiraConfig.getUsername() + ":" + jiraConfig.getApiToken();
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
            .uri(new URI(attachmentUrl))
            .header("Authorization", "Basic " + encodedAuth)
            .build();

        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            if (response.statusCode() == 303) {
                // Handle 303 See Other redirect
                Optional<String> locationHeader = response.headers().firstValue("Location");
                if (locationHeader.isPresent()) {
                    String newAttachmentUrl = locationHeader.get();
                    log.info("Received 303 redirect. Following to: {}", newAttachmentUrl);
                    // Recursively call with the new URL
                    return downloadJiraAttachment(jiraConfig, newAttachmentUrl);
                } else {
                    throw new IOException("Received 303 status but no Location header found.");
                }
            } else {
                throw new IOException("Failed to download attachment. Status code: " + response.statusCode());
            }
        }

        log.info("✅ Successfully downloaded attachment: {} bytes", response.body().length);
        return response.body();
    }

    /**
     * Downloads and converts attachment content to string (for XML/Excel files)
     */
    public String downloadAttachmentAsString(JiraConfig jiraConfig, String attachmentUrl, String encoding) throws Exception {
        byte[] attachmentBytes = downloadJiraAttachment(jiraConfig, attachmentUrl);
        return new String(attachmentBytes, encoding != null ? encoding : StandardCharsets.UTF_8.name());
    }

}
