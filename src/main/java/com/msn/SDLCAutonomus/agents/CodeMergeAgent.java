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
public class CodeMergeAgent {

    private final UtilityService utilityService;


    private static final String CODE_MERGE_AGENT_NAME = "CodeMergeAgent";


    public String runCodeMergeAgent(String existingCode, String newFullFile) {
        log.info("--- 🤖 Starting Code Merge Agent ---");
        LlmAgent mergeAgent = LlmAgent.builder()
            .name(CODE_MERGE_AGENT_NAME)
            .description("Intelligently merges a new full Java file into an existing Java file.")
            .instruction("""
                    You are an expert Java developer specializing in code merging. You will be given two versions of the same Java file: "EXISTING FILE CONTENT" and "NEW FILE CONTENT".

                    Your task is to perform a careful, intelligent merge. Follow these steps precisely:

                    1.  **Identify the differences.** Compare the two files to find what has been added, modified, or removed in the "NEW FILE CONTENT". The "NEW FILE CONTENT" represents the desired state for a new feature, while the "EXISTING FILE CONTENT" contains other, unrelated features that MUST be preserved.

                    2.  **Preserve existing code.** All methods, fields, imports, and annotations from the "EXISTING FILE CONTENT" MUST be kept unless they are explicitly replaced by an updated version in the "NEW FILE CONTENT".

                    3.  **Integrate new code.** Add all new methods, fields, and necessary imports from the "NEW FILE CONTENT" into the "EXISTING FILE CONTENT".

                    4.  **Handle conflicts.** If a method or field exists in both files but has been modified, use the version from the "NEW FILE CONTENT".

                    5.  **Combine imports.** The final code must contain a clean, de-duplicated list of all necessary imports from both files.

                    The final output MUST be a single, complete, and compilable Java file that contains **ALL** features from both the existing and new versions. Do not add any explanation, markers, or ```java ... ``` code blocks. Your response must be only the raw, merged Java code.
                 """)
            .model("gemini-2.0-flash")
            .outputKey("merged_code")
            .build();

        final InMemoryRunner runner = new InMemoryRunner(mergeAgent);
        
        // Pass dynamic content in the user message, not the instruction prompt.
        String combinedInput = String.format("""
            --- EXISTING FILE CONTENT ---
            %s
            --- END EXISTING FILE CONTENT ---

            --- NEW FILE CONTENT ---
            %s
            --- END NEW FILE CONTENT ---
            """, existingCode, newFullFile);

        final Content userMsg = Content.fromParts(Part.fromText(combinedInput));


        try {
            Event finalEvent = utilityService.retryWithBackoff(() -> {
                Session session = runner.sessionService().createSession(runner.appName(), "user-code-merger").blockingGet();
                return runner.runAsync(session.userId(), session.id(), userMsg).blockingLast();
            });
            String mergedCode = finalEvent != null ? finalEvent.stringifyContent().trim() : "";

            // --- NEW: Add detailed logging ---
            if (mergedCode.isEmpty()) {
                log.warn("⚠️ CodeMergeAgent returned an empty response. Falling back to original code.");
            } else {
                log.info("✅ CodeMergeAgent returned merged code. Content length: {}", mergedCode.length());
                // For debugging, log a snippet of the merged code
                log.debug("Merged code snippet:\n---\n{}\n---", mergedCode.substring(0, Math.min(mergedCode.length(), 200)));
            }
            // --- END NEW LOGIC ---

            System.out.println("existingCode: " + existingCode);
            System.out.println("newFullFile: " + newFullFile);
            // If the agent returns an empty response, it's safer to return the original code.
            String result = mergedCode.isEmpty() ? existingCode : mergedCode;
            log.info("--- ✅ Finished Code Merge Agent ---");
            return result;
        } catch (Exception e) {
            log.error("❌ The CodeMergeAgent failed to run. Returning original code. Error: {}", e.getMessage(), e);
            log.info("--- ❌ Finished Code Merge Agent with error ---");
            return existingCode; // Fallback to old code on any failure
        }
    }

}
