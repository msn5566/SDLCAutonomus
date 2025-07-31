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
public class XmlTransformerAgent {

    private final UtilityService utilityService;

    private static final String XML_TRANSFORMER_AGENT_NAME = "XmlTransformerAgent";

    public String runXmlTransformerAgent(String sourceXmlContent, String mappingExcelContent, String sourcePojosJavaCode, String mappingLogicJavaCode) {
        log.info("--- 🤖 Starting XML Transformer Agent ---");
        LlmAgent xmlTransformerAgent = LlmAgent.builder()
                .name(XML_TRANSFORMER_AGENT_NAME)
                .description("Assembles transformation code using provided source XML, mapping logic, and POJO definitions.")
                .instruction("""
                    You are an expert Java developer specializing in data transformation. Your task is to generate runnable Java code that performs the following steps:
                    
                    1. You will be provided with the 'Source XML', the Java code for 'Source POJOs', and the Java code for 'Mapping Logic'.
                    2. Dynamically define and generate Java POJOs for the 'Target XML' structure based on the mapping rules implied by the 'Mapping Logic'. These target POJOs should use Lombok annotations (@Data, @NoArgsConstructor, @AllArgsConstructor) and JAXB annotations (@XmlRootElement, @XmlElement, @XmlAttribute) and be placed in `com.msn.SDLCAutonomus.model.generated.target` package. Ensure the structure mirrors the expected output XML.
                    3. Implement the complete transformation logic in a new Java class. This logic should:
                       a. Include the provided 'Source POJOs' and the newly generated 'Target POJOs'.
                       b. Unmarshal the 'Source XML' into instances of the source POJOs.
                       c. Integrate the provided 'Mapping Logic' to map data from the source POJO fields to the target POJO fields. Ensure the mapping logic (including any validation) is correctly applied.
                       d. Marshal the populated target POJOs back into a 'Target XML' string.
                    
                    **Output Format:**
                    - For **NEW POJO files** (e.g., for target XML structure), use the format with a full path from the project root. Example: `// Create File: src/main/java/com/msn/SDLCAutonomus/model/generated/target/Book.java`
                    - For the **transformation logic implementation**, create a new Java class. Example: `// Create File: src/main/java/com/msn/SDLCAutonomus/service/XmlDataTransformer.java`.
                    - Provide the **complete content** for these new files, ensuring they are runnable and include all necessary imports.

                    Assume the use of JAXB (javax.xml.bind) for XML unmarshalling and marshalling. You may also include `com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlText` if text content is mixed with attributes.

                    Source XML:
                    """ + sourceXmlContent + """

                    Source POJOs:
                    """ + sourcePojosJavaCode + """

                    Mapping Logic (Java Code Snippets):
                    """ + mappingLogicJavaCode + """
                """)
                .model("gemini-2.0-flash")
                .build();

        try {
            final InMemoryRunner runner = new InMemoryRunner(xmlTransformerAgent);
            final Content userMsg = Content.fromParts(
                Part.fromText("Source XML:\n" + sourceXmlContent),
                Part.fromText("Source POJOs:\n" + sourcePojosJavaCode),
                Part.fromText("Mapping Logic:\n" + mappingLogicJavaCode)
            );

            Event finalEvent = utilityService.retryWithBackoff(() -> {
                Session session = runner.sessionService().createSession(runner.appName(), "user-xml-transformer-assembler").blockingGet();
                return runner.runAsync(session.userId(), session.id(), userMsg).blockingLast();
            });
            log.info("--- ✅ Finished XML Transformer Agent ---");
            return finalEvent != null ? finalEvent.stringifyContent() : "XML Transformer Agent failed to produce transformation code.";
        } catch (Exception e) {
            log.error("❌ The XML Transformer Agent itself failed to run.", e);
            log.info("--- ❌ Finished XML Transformer Agent with error ---");
            return "XML Transformer Agent execution failed: " + e.getMessage();
        }
    }
} 