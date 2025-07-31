package com.msn.SDLCAutonomus.agents;

import org.springframework.stereotype.Service;

import com.google.adk.agents.LlmAgent;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.events.Event;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.msn.SDLCAutonomus.service.UtilityService;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@AllArgsConstructor
public class ExcelAgent {

    private final UtilityService utilityService;

    private static final String EXCEL_AGENT_NAME = "ExcelAgent";

    public String runExcelAgent(String sourcePojosJavaCode, String mappingExcelContent) {
        log.info("--- 🤖 Starting Excel Agent ---");
        LlmAgent excelAgent = LlmAgent.builder()
                .name(EXCEL_AGENT_NAME)
                .description("Analyzes Excel mapping data and generates Java code for data transformation logic and validation.")
                .instruction("""
                    You are an expert Java developer specializing in data mapping and validation logic generation. Your task is to analyze the provided 'Source POJO Definitions' (Java code for POJO classes generated from source XML) and the 'Mapping Excel Data' (CSV string containing SourcePath, TargetPath, TransformationRule, and ValidationRule).
                    
                    Your primary goal is to generate **ONLY the Java code snippets for the transformation and validation logic**. This code will be integrated into a larger transformation class.
                    
                    Guidelines:
                    1. The 'Mapping Excel Data' is a CSV where the first row is headers. The columns are `SourcePath`, `TargetPath`, `TransformationRule`, and `ValidationRule`.
                    2. Parse the CSV data row by row. For each row, you need to generate Java code that:
                       a. **Accesses the source field:** Based on `SourcePath` (e.g., `Book.chapters.chapter[0].content` or `element.attributeName`), generate Java code to safely retrieve the value from an instance of the source POJO.
                       b. **Applies Validation (if `ValidationRule` is present):** Intelligently analyze and interpret the `ValidationRule` string. Generate appropriate Java code to validate the retrieved `sourceValue`. If validation fails, log a warning and skip mapping that specific field. Ensure the generated validation code is robust and handles various data types and edge cases (e.g., null values).
                       c. **Applies Transformation (if `TransformationRule` is present):** Generate Java code to apply the `TransformationRule` to the `sourceValue`. The rules are as defined for the XmlTransformerAgent (e.g., `COPY`, `CONCAT`, `TO_INT`, `CUSTOM_JAVA(javaExpression)`).
                       d. **Prepares for Target Field Setting:** Generate Java code that prepares the transformed value to be set into the `TargetPath` of the target POJO. This might involve creating intermediate objects for nested paths.
                    3. The generated code should be a sequence of Java statements or methods that can be placed inside a larger transformation method. It should *not* be a complete class, nor include imports or package declarations.
                    4. Assume that the source POJO instance is available as a variable named `sourceObject` (or a more specific type if known, e.g., `SourceRootType sourceObject`), and the target POJO instance is available as `targetObject`.
                    5. For collections (e.g., `List<Chapter>`), generate a loop to iterate over source collection elements and apply mapping/validation for each element.
                    6. Provide only the Java code. Do NOT include any explanations, markdown outside the code, or extra text.

                    Source POJO Definitions:
                    """ + sourcePojosJavaCode + """

                    Mapping Excel Data (CSV):
                    """ + mappingExcelContent + """
                """)
                .model("gemini-2.0-flash")
                .build();

        try {
            final InMemoryRunner runner = new InMemoryRunner(excelAgent);
            final Content userMsg = Content.fromParts(
                Part.fromText("Source POJO Definitions:\n" + sourcePojosJavaCode),
                Part.fromText("Mapping Excel Data:\n" + mappingExcelContent)
            );

            Event finalEvent = utilityService.retryWithBackoff(() -> {
                Session session = runner.sessionService().createSession(runner.appName(), "user-excel-mapper-logic-generator").blockingGet();
                return runner.runAsync(session.userId(), session.id(), userMsg).blockingLast();
            });
            log.info("--- ✅ Finished Excel Agent ---");
            return finalEvent != null ? finalEvent.stringifyContent() : "Excel Agent failed to produce mapping logic code.";
        } catch (Exception e) {
            log.error("❌ The Excel Agent itself failed to run.", e);
            log.info("--- ❌ Finished Excel Agent with error ---");
            return "Excel Agent execution failed: " + e.getMessage();
        }
    }
} 