package com.msn.SDLCAutonomus.agents;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.msn.SDLCAutonomus.model.ExtractedConfig;
import com.msn.SDLCAutonomus.model.GitConfig;
import com.msn.SDLCAutonomus.model.ProjectConfig;
import com.msn.SDLCAutonomus.service.UtilityService;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@AllArgsConstructor
public class ExtractedConfigAgent {

    private final UtilityService utilityService;

    private static final String SRS_KEY_GITHUB_URL = "GitHub-URL";
    private static final String SRS_KEY_CHECKOUT_BRANCH = "checkout_branch";
    private static final String SRS_KEY_REPO_NAME = "Repository-Name";
    private static final String SRS_KEY_JAVA_VERSION = "Java-Version";
    private static final String SRS_KEY_SPRING_BOOT_VERSION = "SpringBoot-Version";
    private static final String SRS_KEY_PACKAGE_NAME = "Package-Name";

    public ExtractedConfig runConfigAgent(String srsContent) throws IOException {
        log.info("--- 🤖 Starting Config Agent ---");
        LlmAgent configAgent = LlmAgent.builder()
                .name("ConfigAgent")
                .description("Extracts all key project configurations from a Jira user story.")
                .instruction("""
                    You are an expert configuration parser. Analyze the following Jira user story text.
                    Your task is to intelligently extract values for a predefined set of configuration keys.

                    **Be flexible with the input format.** The keys in the story text might be phrased differently, have different casing, or lack hyphens. You must map them to the canonical keys below.
                    - **Handle Partial Versions**: If the `SpringBoot-Version` in the text is a partial or wildcard version (e.g., `3.2.x`, `3.2.*`, or just `3.2`), you MUST ignore it and use the default version instead. Only use a version from the text if it is a complete, concrete version (e.g., `3.5.3`).
                    For example:
                    - "java 21", "java-version: 21", or "Java Version 21" should all map to `Java-Version: 21`.
                    - "spring boot 3.5.3" should be mapped to `SpringBoot-Version: 3.5.3`.
                    - "spring boot 3.2.x" or "springboot-version: 3.2" MUST be ignored, and you should use the default.
                    - "Repo Name my-project" or "Repository-Name: my-project" should map to `Repository-Name: my-project`.

                    **Output Format:**
                    You MUST respond with ONLY the canonical key-value pairs, one per line. Do not include any other text or explanation.

                    **Canonical Keys to Extract:**
                    - `GitHub-URL`
                    - `checkout_branch`
                    - `Repository-Name`
                    - `Java-Version`
                    - `SpringBoot-Version`
                    - `Package-Name`

                    **Default Values:**
                    If a value is not specified for `Java-Version`, `SpringBoot-Version`, or if the `SpringBoot-Version` is partial/wildcard, you MUST use the following default values:
                    - `Java-Version: 17`
                    - `SpringBoot-Version: 3.5.3`
                    If `Package-Name` is not specified, default to `com.generated.microservice`.

                    **Mandatory Keys:**
                    The keys `GitHub-URL`, `checkout_branch`, and `Repository-Name` are mandatory. If you cannot find them in the text, respond with an empty value for that key.
                    """)
                .model("gemini-2.0-flash")
                .outputKey("config")
                .build();

        try {
            final InMemoryRunner runner = new InMemoryRunner(configAgent);
            final Content userMsg = Content.fromParts(Part.fromText(srsContent));

            Event finalEvent = utilityService.retryWithBackoff(() -> {
                Session session = runner.sessionService().createSession(runner.appName(), "user-config-analyzer").blockingGet();
                return runner.runAsync(session.userId(), session.id(), userMsg).blockingLast();
            });

            String response = finalEvent != null ? finalEvent.stringifyContent() : "";
            log.debug("ConfigAgent Response:\\n{}", response);

            String repoUrl = parseSrsForValue(response, SRS_KEY_GITHUB_URL);
            String baseBranch = parseSrsForValue(response, SRS_KEY_CHECKOUT_BRANCH);
            String repoPath = parseSrsForValue(response, SRS_KEY_REPO_NAME);
            String javaVersion = parseSrsForValue(response, SRS_KEY_JAVA_VERSION);
            String springBootVersion = parseSrsForValue(response, SRS_KEY_SPRING_BOOT_VERSION);
            String packageName = parseSrsForValue(response, SRS_KEY_PACKAGE_NAME);

            // --- NEW: Sanitize the repository URL to handle formatting issues from Jira ---
            if (repoUrl != null && repoUrl.contains("|")) {
                log.warn("Malformed repository URL detected: '{}'. Sanitizing...", repoUrl);
                repoUrl = repoUrl.split("\\|")[0].trim();
                log.info("Sanitized URL: '{}'", repoUrl);
            }
            // --- END NEW LOGIC ---

            // --- NEW: Validate mandatory fields and fail fast ---
            List<String> missingKeys = new ArrayList<>();
            if (repoUrl == null || repoUrl.isBlank()) missingKeys.add(SRS_KEY_GITHUB_URL);
            if (baseBranch == null || baseBranch.isBlank()) missingKeys.add(SRS_KEY_CHECKOUT_BRANCH);
            if (repoPath == null || repoPath.isBlank()) missingKeys.add(SRS_KEY_REPO_NAME);

            if (!missingKeys.isEmpty()) {
                String errorMessage = "ConfigAgent failed to extract mandatory keys: " + String.join(", ", missingKeys)
                    + ". Please ensure they are present and have values in the Jira user story description.";
                throw new IOException(errorMessage);
            }
            // --- END NEW LOGIC ---

            GitConfig gitConfig = new GitConfig(repoUrl, baseBranch, repoPath);
            ProjectConfig projectConfig = new ProjectConfig(javaVersion, springBootVersion, packageName);

            log.info("--- ✅ Finished Config Agent ---");
            return new ExtractedConfig(gitConfig, projectConfig);
        } catch (IOException e) {
            // Re-throw IOExceptions (from our validation) directly
            log.info("--- ❌ Finished Config Agent with error ---");
            throw e;
        } catch (Exception e) {
            String errorMessage = "The Config Agent failed to execute due to an internal error.";
            log.error("❌ " + errorMessage, e);
            // Wrap other exceptions in IOException to signal a configuration failure
            log.info("--- ❌ Finished Config Agent with error ---");
            throw new IOException(errorMessage, e);
        }
    }


    private static String parseSrsForValue(String srsContent, String key) {
        Pattern pattern = Pattern.compile("^" + key + ":\\s*(.+)$", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(srsContent);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

}
