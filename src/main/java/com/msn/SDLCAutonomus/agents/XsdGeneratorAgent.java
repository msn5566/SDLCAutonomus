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
public class XsdGeneratorAgent {

    private final UtilityService utilityService;

    private static final String XSD_GENERATOR_AGENT_NAME = "XsdGeneratorAgent";

    public String runXsdGeneratorAgent(String sourceXmlContent, String targetXmlContent, String mappingJsonContent) {
        log.info("--- 🤖 Starting XSD Generator Agent ---");
        LlmAgent xsdGeneratorAgent = LlmAgent.builder()
                .name(XSD_GENERATOR_AGENT_NAME)
                .description("Generates an XSD schema from XML content, incorporating validation rules from a JSON mapping file.")
                .instruction("""
                    You are an expert in XML Schema Definition (XSD). Your task is to generate a single XSD schema that defines the structure and validates the content of both the Source XML and the Target XML, incorporating the validation rules specified in the Mapping JSON Data.
                     
                    **CRITICAL INSTRUCTIONS:**
                    1. **Output Format:**
                    - For a **NEW file**, use the format with a full path from the project root. Example: `// Create File: src/main/resorces/mapplingfile.xsd`
                    - For **MODIFYING an existing file**, use the format with a full path from the project root. Example: `// Modify File: src/main/resorces/mapplingfile.xsd`

                    Guidelines:
                    1.  Analyze the 'Source XML' and 'Target XML' to infer their structures and data types.
                    2.  Parse the 'Mapping JSON Data'. For each mapping, extract validation rules (e.g., 'required', 'type', 'minLength', 'maxLength', 'pattern', 'min', 'max').
                    3.  Apply these validation rules to the corresponding elements/attributes in the generated XSD for *both* source and target schemas. For example:
                        -   `required: true` maps to `minOccurs="1"`.
                        -   `type: string` maps to `xs:string`.
                        -   `minLength: N` maps to `xs:minLength value="N"` facet.
                        -   `maxLength: N` maps to `xs:maxLength value="N"` facet.
                        -   `pattern: "regex"` maps to `xs:pattern value="regex"` facet.
                        -   `min: N` (for numeric) maps to `xs:minInclusive value="N"` facet.
                        -   `max: N` (for numeric) maps to `xs:maxInclusive value="N"` facet.
                        -   `occurrence` (for arrays) maps to `minOccurs` and `maxOccurs`.
                    4.  Ensure the generated XSD is valid and self-contained.
                    5.  Provide only the XSD content. Do NOT include any explanations, markdown outside the XSD, or extra text.
                
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
            final InMemoryRunner runner = new InMemoryRunner(xsdGeneratorAgent);
            final Content userMsg = Content.fromParts(
                Part.fromText("Source XML:\n" + sourceXmlContent),
                Part.fromText("Target XML:\n" + targetXmlContent),
                Part.fromText("Mapping JSON Data:\n" + mappingJsonContent)
            );

            Event finalEvent = utilityService.retryWithBackoff(() -> {
                Session session = runner.sessionService().createSession(runner.appName(), "user-xsd-generator").blockingGet();
                return runner.runAsync(session.userId(), session.id(), userMsg).blockingLast();
            });
            log.info("--- ✅ Finished XSD Generator Agent ---");
            return finalEvent != null ? finalEvent.stringifyContent() : "XSD Generator Agent failed to produce XSD.";
        } catch (Exception e) {
            log.error("❌ The XSD Generator Agent itself failed to run.", e);
            log.info("--- ❌ Finished XSD Generator Agent with error ---");
            return "XSD Generator Agent execution failed: " + e.getMessage();
        }
    }
}