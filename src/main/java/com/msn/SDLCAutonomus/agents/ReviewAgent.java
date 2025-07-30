package com.msn.SDLCAutonomus.agents;

import org.springframework.stereotype.Service;

import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.msn.SDLCAutonomus.service.UtilityService;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@AllArgsConstructor
public class ReviewAgent {
    
    private final UtilityService utilityService;

    private static final String REVIEW_AGENT_NAME = "ReviewAgent";

    public String runReviewAgent(String buildLog) {
        log.info("--- 🤖 Starting Review Agent ---");
        LlmAgent reviewAgent = LlmAgent.builder()
                .name(REVIEW_AGENT_NAME)
                .description("Analyzes Maven build logs to find the root cause of a failure.")
                .instruction("""
                    You are an expert Java build engineer. You will be given the full log output from a failed Maven build (`mvn clean verify`).
                    Your task is to analyze the log, identify the primary root cause of the failure, and provide a concise, human-readable summary.

                    Focus on the first critical error you find (e.g., a Compilation Error, a specific test failure).
                    Explain what the error means and suggest a likely solution. Do not provide full code, just a clear explanation.

                    Example Analysis:
                    The build failed due to a compilation error in `EmployeeController.java`.
                    The error `package javax.validation does not exist` indicates that the code is using the old package name for Java Validation.
                    The fix is to update the import statements to use the `jakarta.validation` package, which is standard in Spring Boot 3, and to ensure the `spring-boot-starter-validation` dependency is included in the pom.xml.
                    """)
                .model("gemini-2.0-flash")
                .outputKey("review")
                .build();

        try {
            // Use the simpler, synchronous-style run method that handles session creation internally.
            final InMemoryRunner runner = new InMemoryRunner(reviewAgent);
            final Content userMsg = Content.fromParts(Part.fromText(buildLog));

            Event finalEvent = utilityService.retryWithBackoff(() -> {
                Session session = runner.sessionService().createSession(runner.appName(), "user-review-analyzer").blockingGet();
                return runner.runAsync(session.userId(), session.id(), userMsg).blockingLast();
            });
            log.info("--- ✅ Finished Review Agent ---");
            return finalEvent != null ? finalEvent.stringifyContent() : "Review Agent failed to produce an analysis.";
        } catch (Exception e) {
            log.error("❌ The Review Agent itself failed to run.", e);
            log.info("--- ❌ Finished Review Agent with error ---");
            return "Review Agent execution failed: " + e.getMessage();
        }
    }

}
