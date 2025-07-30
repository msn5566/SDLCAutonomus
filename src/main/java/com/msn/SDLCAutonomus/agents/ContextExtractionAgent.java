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
public class ContextExtractionAgent {

    private final UtilityService utilityService;

    private static final String CONTEXT_EXTRACTION_AGENT_NAME = "ContextExtractionAgent";

    public String runContextExtractionAgent(String existingFileContent) {
        log.info("--- 🤖 Starting Context Extraction Agent ---");
        LlmAgent contextAgent = LlmAgent.builder()
            .name(CONTEXT_EXTRACTION_AGENT_NAME)
            .description("Extracts class-level context and conventions from an existing Java file for use in code generation.")
            .instruction("""
                    You are an expert Java code analyst. Given the full content of a Java class, extract the following information as a structured summary for use in code generation:
                    - The class name and its type (e.g., Controller, Service, Repository, Entity)
                    - All class-level annotations (e.g., @RestController, @RequestMapping, @Service)
                    - The value of any base @RequestMapping or similar annotation
                    - All static variables/constants (names and values)
                    - All field declarations (names, types, and annotations)
                    - The names of injected dependencies (e.g., services, repositories)
                    - Any naming conventions for objects or references

                    Output the information as a structured summary, e.g.:
                    Class: EmployeeController
                    Type: Controller
                    Class-level Annotations: @RestController, @RequestMapping("/employees")
                    Base RequestMapping: /employees
                    Static Variables: [String API_VERSION = "v1"]
                    Fields: [private final EmployeeService employeeService]
                    Injected Dependencies: [employeeService]
                    Naming Conventions: [employeeService for EmployeeService]

                    Do not include any code, only the structured summary.
                    """)
            .model("gemini-2.0-flash")
            .outputKey("context")
            .build();

        final InMemoryRunner runner = new InMemoryRunner(contextAgent);
        final Content userMsg = Content.fromParts(Part.fromText(existingFileContent));

        try {
            Event finalEvent = utilityService.retryWithBackoff(() -> {
                Session session = runner.sessionService().createSession(runner.appName(), "user-context-extractor").blockingGet();
                return runner.runAsync(session.userId(), session.id(), userMsg).blockingLast();
            });
            String contextSummary = finalEvent != null ? finalEvent.stringifyContent().trim() : "";
            log.info("✅ ContextExtractionAgent summary:\n{}", contextSummary);
            log.info("--- ✅ Finished Context Extraction Agent ---");
            return contextSummary;
        } catch (Exception e) {
            log.error("❌ The ContextExtractionAgent failed to run. Returning empty context. Error: {}", e.getMessage(), e);
            log.info("--- ❌ Finished Context Extraction Agent with error ---");
            return "";
        }
    }

}
