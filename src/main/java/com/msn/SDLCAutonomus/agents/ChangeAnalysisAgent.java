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
public class ChangeAnalysisAgent {

    private final UtilityService utilityService;

    private static final String CHANGE_ANALYSIS_AGENT_NAME = "ChangeAnalysisAgent";
    private static final String KEY_CHANGE_ANALYSIS = "change_analysis";


    public String runChangeAnalysisAgent(String oldSrs, String newSrs) {
        log.info("--- 🤖 Starting Change Analysis Agent ---");
        LlmAgent changeAgent = LlmAgent.builder()
                .name(CHANGE_ANALYSIS_AGENT_NAME)
                .description("Compares old and new Jira stories to generate a changelog.")
                .instruction("""
                        You will be given an old and a new version of a Jira user story, separated by markers.
                        Analyze the differences and generate a concise, human-readable changelog in Markdown format.
                        Focus on added, removed, and modified features. If the old story is empty, state that this is the initial version of the project.
                        If there are no functional changes between the two versions, respond with ONLY the text "No changes detected.".
                        """)
                .model("gemini-2.0-flash")
                .outputKey(KEY_CHANGE_ANALYSIS)
                .build();

        String combinedInput = "--- OLD SRS ---\n" + oldSrs + "\n\n--- NEW SRS ---\n" + newSrs;

        // Use the simpler, synchronous-style run method that handles session creation internally.
        // This is more robust for single-shot agent invocations and avoids potential session state issues.
        final InMemoryRunner runner = new InMemoryRunner(changeAgent);
        final Content userMsg = Content.fromParts(Part.fromText(combinedInput));

        Event finalEvent = utilityService.retryWithBackoff(() -> {
            Session session = runner.sessionService().createSession(runner.appName(), "user-change-analyzer").blockingGet();
            return runner.runAsync(session.userId(), session.id(), userMsg).blockingLast();
        });
        log.info("--- ✅ Finished Change Analysis Agent ---");
        return finalEvent != null ? finalEvent.stringifyContent() : "";
    }

}
