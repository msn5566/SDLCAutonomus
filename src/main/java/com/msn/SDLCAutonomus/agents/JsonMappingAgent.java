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
public class JsonMappingAgent {

    private final UtilityService utilityService;

    private static final String JSON_MAPPING_AGENT_NAME = "JsonMappingAgent";

    public String runJsonMappingAgent(String sourceXmlContent, String mappingJsonContent, String targetXmlContent) {
        log.info("--- 🤖 Starting JSON Mapping Agent (Full Transformation Mode) ---");
        LlmAgent jsonMappingAgent = LlmAgent.builder()
                .name(JSON_MAPPING_AGENT_NAME)
                .description("Generates complete, runnable Java code for XML data transformation, including source POJOs, target POJOs, and mapping logic based on JSON rules.")
                .instruction("""
                    You are an expert Java developer specializing in end-to-end XML data transformation. Your task is to generate **complete, runnable Java code** for an `XmlDataTransformer.java` service class. This class will encapsulate the entire transformation process.

                    **CRITICAL INSTRUCTIONS:**
                    1.  **Generate a single Service Class**: Create a new Java class named `XmlDataTransformer.java` in the `com.msn.SDLCAutonomus.service` package. This class MUST be annotated with `@Service` and `@Slf4j` (for logging). All dependencies should be injected via constructor. It should contain the logic for unmarshalling source XML, applying the transformation based on the mapping JSON, and marshalling to target XML.
                    2.  **Transformation Logic**: Implement the data transformation process within this `XmlDataTransformer` class. This involves:
                        a.  **Unmarshal Source XML**: Unmarshal the 'Source XML' into existing source POJOs. Assume these POJOs are already generated and available in `com.msn.SDLCAutonomus.model.generated.source`.
                        b.  **Marshal Target XML**: Marshal the populated target POJOs into a 'Target XML' string. Assume these POJOs are already generated and available in `com.msn.SDLCAutonomus.model.generated.target`.
                        c.  **Apply Mapping Rules**: Integrate the 'Mapping JSON Data' to map data from the source POJO fields to the target POJO fields. This involves:
                            -   Accessing source fields based on `sourceField` (e.g., `Order.Customer.Name`). Handle nested objects and array traversals.
                            -   **NOTE**: XML validation against XSD is handled upstream. You do NOT need to generate Java code for validation rules (e.g., `required`, `type`, `minLength`, `maxLength`, `pattern`, `min`, `max`, `occurrence`). Focus only on the data transformation itself.
                            -   Applying Transformation (if `transform` is present): Implement rules like `booleanToPaidStatus` (map `true` to "Paid", `false` to "Unpaid"), `TO_INT`, `TO_STRING`, `TO_DECIMAL`, `CONCAT(field1,field2)`.
                            -   Preparing for Target Field Setting: Generate Java code to set the transformed value into the `targetField` of the target POJO, creating intermediate objects for nested paths as needed.
                            -   Handling Collections (`[]` suffix): If `sourceField` or `targetField` indicate a collection, generate a loop to iterate over source collection elements and apply mappings/validations for each element. The `children` array within a mapping object defines the mappings for elements within the collection.

                    **Output Format:**
                    -   For the **transformation logic implementation**, use the format with a full path from the project root. Example: `// Create File: src/main/java/com/msn/SDLCAutonomus/service/XmlDataTransformer.java`.
                    -   Provide the **complete content** for this new file, ensuring it is runnable and includes all necessary imports.

                    Assume the use of JAXB (javax.xml.bind) for XML unmarshalling and marshalling. You may also include `com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlText` if text content is mixed with attributes.

                    Source XML:
                    """ + sourceXmlContent + """

                    Target XML:
                    """ + targetXmlContent + """

                    Mapping JSON Data:
                    """ + mappingJsonContent + """
                """)
                .model("gemini-2.0-flash")
                .build();

        try {
            final InMemoryRunner runner = new InMemoryRunner(jsonMappingAgent);
            final Content userMsg = Content.fromParts(
                Part.fromText("Source XML:\n" + sourceXmlContent),
                Part.fromText("Target XML:\n" + targetXmlContent),
                Part.fromText("Mapping JSON Data:\n" + mappingJsonContent)
            );

            Event finalEvent = utilityService.retryWithBackoff(() -> {
                Session session = runner.sessionService().createSession(runner.appName(), "user-json-full-transformer-generator").blockingGet();
                return runner.runAsync(session.userId(), session.id(), userMsg).blockingLast();
            });
            log.info("--- ✅ Finished JSON Mapping Agent (Full Transformation Mode) ---");
            return finalEvent != null ? finalEvent.stringifyContent() : "JSON Mapping Agent failed to produce full transformation code.";
        } catch (Exception e) {
            log.error("❌ The JSON Mapping Agent (Full Transformation Mode) itself failed to run.", e);
            log.info("--- ❌ Finished JSON Mapping Agent (Full Transformation Mode) with error ---");
            return "JSON Mapping Agent execution failed: " + e.getMessage();
        }
    }
}