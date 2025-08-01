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
public class XmlPojoAgent {

    private final UtilityService utilityService;

    private static final String XML_POJO_AGENT_NAME = "XmlPojoAgent";

    public String runXmlPojoAgent(String xmlContent) {
        log.info("--- 🤖 Starting XML POJO Agent ---");
        LlmAgent xmlPojoAgent = LlmAgent.builder()
                .name(XML_POJO_AGENT_NAME)
                .description("Reads XML content and generates corresponding Java POJO classes.")
                .instruction("""
                    You are an expert Java developer. Your task is to analyze the provided XML content and generate appropriate Java POJO (Plain Old Java Object) classes. Each class should represent an XML element, with fields corresponding to attributes and child elements.
                    
                    **CRITICAL INSTRUCTIONS:**
                    1. **Output Format:**
                    - For a **NEW file**, use the format with a full path from the project root. Example: `// Create File: src/main/java/com/yourcompany/service/UserService.java`
                    - For **MODIFYING an existing file**, use the format with a full path from the project root. Example: `// Modify File: src/main/java/com/yourcompany/service/UserService.java`

                    Guidelines:
                    1. For each XML element, create a Java class with a name derived from the element name (e.g., <book> -> Book).
                    2. Use Lombok annotations (@Data, @NoArgsConstructor, @AllArgsConstructor) for boilerplate code.
                    3. Map XML attributes to Java fields. Use appropriate Java data types (String, int, double, boolean, etc.).
                    4. Map XML child elements to Java fields. If a child element can appear multiple times, use `java.util.List`.
                    5. If an element has both text content and attributes/child elements, use `@JacksonXmlText` for the text content and `@JacksonXmlProperty` for attributes/child elements.
                    6. Use JAXB annotations (@XmlRootElement, @XmlElement, @XmlAttribute) to enable XML marshalling and unmarshalling, if applicable.
                    7. Ensure all classes have proper getters and setters, or use Lombok's @Data.
                    8. Provide only the Java code, no extra explanations or markdown.
                    9. All generated classes should be in the package `com.msn.SDLCAutonomus.model.generated`.

                    Example XML Input:
                    <book id="123">
                        <title>The Great Novel</title>
                        <author>John Doe</author>
                        <chapters>
                            <chapter number="1">Introduction</chapter>
                            <chapter number="2">Body</chapter>
                        </chapters>
                    </book>

                    Example Java Output:
                    package com.msn.SDLCAutonomus.model.generated;

                    import lombok.Data;
                    import lombok.NoArgsConstructor;
                    import lombok.AllArgsConstructor;
                    import javax.xml.bind.annotation.XmlAttribute;
                    import javax.xml.bind.annotation.XmlElement;
                    import javax.xml.bind.annotation.XmlRootElement;
                    import java.util.List;

                    @Data
                    @NoArgsConstructor
                    @AllArgsConstructor
                    @XmlRootElement(name = "book")
                    public class Book {
                        @XmlAttribute
                        private String id;
                        @XmlElement
                        private String title;
                        @XmlElement
                        private String author;
                        @XmlElement(name = "chapters")
                        private Chapters chapters;
                    }

                    @Data
                    @NoArgsConstructor
                    @AllArgsConstructor
                    public class Chapters {
                        @XmlElement(name = "chapter")
                        private List<Chapter> chapter;
                    }

                    @Data
                    @NoArgsConstructor
                    @AllArgsConstructor
                    public class Chapter {
                        @XmlAttribute
                        private int number;
                        @JacksonXmlText
                        private String content;
                    }

                    Generate POJOs for the following XML:
                    """ + xmlContent + """
                """)
                .model("gemini-2.0-flash")
                .build();

        try {
            final InMemoryRunner runner = new InMemoryRunner(xmlPojoAgent);
            final Content userMsg = Content.fromParts(Part.fromText(xmlContent));

            Event finalEvent = utilityService.retryWithBackoff(() -> {
                Session session = runner.sessionService().createSession(runner.appName(), "user-xml-pojo-generator").blockingGet();
                return runner.runAsync(session.userId(), session.id(), userMsg).blockingLast();
            });
            log.info("--- ✅ Finished XML POJO Agent ---");
            return finalEvent != null ? finalEvent.stringifyContent() : "XML POJO Agent failed to produce POJOs.";
        } catch (Exception e) {
            log.error("❌ The XML POJO Agent itself failed to run.", e);
            log.info("--- ❌ Finished XML POJO Agent with error ---");
            return "XML POJO Agent execution failed: " + e.getMessage();
        }
    }
} 