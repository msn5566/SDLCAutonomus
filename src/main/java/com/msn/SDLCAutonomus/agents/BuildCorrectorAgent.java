package com.msn.SDLCAPI.agents;

import org.springframework.stereotype.Service;

import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.msn.SDLCAPI.service.UtilityService;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@AllArgsConstructor
public class BuildCorrectorAgent {

    private static final String BUILD_CORRECTOR_AGENT_NAME = null;
    private final UtilityService utilityService;

    public String runBuildCorrectorAgent(String buildLog, String reviewAnalysis, String allSourceFiles) {
        log.info("--- 🤖 Starting Build Corrector Agent ---");
        LlmAgent correctorAgent = LlmAgent.builder()
                .name(BUILD_CORRECTOR_AGENT_NAME)
                .description("Analyzes build failures and corrects the faulty Java code across the entire project.")
                .instruction("""
You are a Senior Software Engineer specializing in debugging and fixing build failures. You will be given a Maven build log, an analysis of the failure, and the full content of ALL source files in the project.

Your task is to identify the root cause of the build failure and provide the corrected code for ALL files that need to be changed to fix the error.

**CRITICAL INSTRUCTIONS:**
1.  **Analyze the `BUILD LOG` and `REVIEW ANALYSIS`** to understand the root cause. The error may be in a different file than where the compiler reports it. For example, a missing method in a Repository will cause a compilation error in a Service that calls it. The fix is to add the method to the Repository.
2.  **Examine ALL `PROJECT SOURCE FILES`** to understand the full context.
3.  **Generate Corrected Code:** For each file that needs to be modified, you MUST provide its full and complete corrected content.
4.  **Output Format:** You MUST format your response as one or more code blocks.
    - For a file that needs to be **REFACTORED**, start the block with `// Refactored File: [full/path/to/file.java]`.
    - For a file that needs to be **CREATED** (less common for a fix, but possible), start the block with `// Create File: [full/path/to/file.java]`.
    - Follow the marker with the complete, corrected code for that file.
5.  **Do not add any other explanation or text.** Your entire response must be only the file markers and their corresponding code blocks.
""")
                .model("gemini-2.0-flash")
                .outputKey("corrected_code")
                .build();

        final InMemoryRunner runner = new InMemoryRunner(correctorAgent);
        final Content userMsg = Content.fromParts(
            Part.fromText("**BUILD LOG:**\n" + buildLog),
            Part.fromText("\n**REVIEW ANALYSIS:**\n" + reviewAnalysis),
            Part.fromText("\n**PROJECT SOURCE FILES:**\n" + allSourceFiles)
        );

        try {
            Event finalEvent = utilityService.retryWithBackoff(() -> {
                Session session = runner.sessionService().createSession(runner.appName(), "user-build-corrector").blockingGet();
                return runner.runAsync(session.userId(), session.id(), userMsg).blockingLast();
            });
            String response = finalEvent != null ? finalEvent.stringifyContent().trim() : "";
            log.info("Full raw response from BuildCorrectorAgent:\n---\n{}\n---", response);
            // The response can be directly passed to writeClassesToFileSystem, so we just return it.
            if (!response.isBlank()) {
                log.info("--- ✅ Finished Build Corrector Agent ---");
                return response;
            } else {
                log.warn("BuildCorrectorAgent returned an empty response.");
                log.info("--- ❌ Finished Build Corrector Agent with empty response ---");
                return null;
            }
        } catch (Exception e) {
            log.error("❌ The BuildCorrectorAgent failed to run. Error: {}", e.getMessage(), e);
            log.info("--- ❌ Finished Build Corrector Agent with error ---");
            return null;
        }
    }

}
