package com.msn.SDLCAutonomus.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.msn.SDLCAutonomus.agents.CodeMergeAgent;
import com.msn.SDLCAutonomus.model.ProjectConfig;
import com.msn.SDLCAutonomus.model.WorkflowResult;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@AllArgsConstructor
public class WriteClassesToFileSystemService {

    
    // --- Constants for File System and Git ---
    private static final String AI_STATE_DIR = ".ai-state";
    private static final String JIRA_STATE_FILE_NAME = "jira_issue.txt";
    private static final String CHANGELOG_FILE_NAME = "AI_CHANGELOG.md";
    
    private final CodeMergeAgent codeMergeAgent;


    public void generateProjectFiles(String repoName, WorkflowResult result, String srsContent, String changeAnalysis, ProjectConfig projectConfig, String featureBranch) {
        writeClassesToFileSystem(result.getCodeAndTestOutput(), repoName);

        if (result.getDependencyList().isEmpty()) {
            log.warn("⚠️ DependencyAgent did not return any dependencies. Falling back to default pom.xml.");
            List<String> defaultDeps = List.of(
                "org.springframework.boot:spring-boot-starter-web",
                "org.springframework.boot:spring-boot-starter-data-jpa",
                "org.postgresql:postgresql:runtime",
                "org.projectlombok:lombok:optional"
            );
            addPomXml(repoName, defaultDeps, projectConfig);
        } else {
            addPomXml(repoName, result.getDependencyList(), projectConfig);
        }

        // For README, we'll create the full summary content first.
        StringBuilder readmeContent = new StringBuilder();
        readmeContent.append("## 📝 Project Summary\n\n")
            .append(result.getRequirementsSummary())
            .append("\n\n### 🛠️ Core Dependencies\n\n");
        for(String dep : result.getDependencyList()) {
            readmeContent.append("- `").append(dep).append("`\n");
        }

        // Append to changelog, state file, and README to keep a running history.
        appendContentWithMetadata(Paths.get(repoName, CHANGELOG_FILE_NAME), changeAnalysis, featureBranch);
        appendContentWithMetadata(Paths.get(repoName, AI_STATE_DIR, JIRA_STATE_FILE_NAME), srsContent, featureBranch);
        appendContentWithMetadata(Paths.get(repoName, "README.md"), readmeContent.toString(), featureBranch);

        addApplicationYml(repoName);
        addGithubActionsCiConfig(repoName);
    }


    public void writeClassesToFileSystem(String combinedOutput, String baseDir) {
        // Regex to capture the action (Create/Modify), file path, and the code block.
        // It looks for a marker like "// Create File: " or "// Modify File: "
        Pattern pattern = Pattern.compile("// (Create File|Modify File|Refactored File): ([^\\n]+)\\s*\\n(.*?)(?=\\n// (?:Create|Modify|Refactored) File:|$)", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(combinedOutput);
        System.out.println("==========combinedOutput start: =================");
        System.out.println(combinedOutput);
        System.out.println("============combinedOutput end=================");

        while (matcher.find()) {
            String action = matcher.group(1).trim();
            String relativePath = matcher.group(2).trim();
            String rawContent = matcher.group(3).trim();

            System.out.println("action: " + action);
            System.out.println("relativePath: " + relativePath);
            System.out.println("rawContent: " + rawContent);

            if (!rawContent.startsWith("```")) {
                rawContent = "```java\n" + rawContent ;
            }

            String content = filteredContent(rawContent);

            

            if (content.isEmpty()) {
                log.warn("⚠️ Skipping empty code block for {}", relativePath);
                continue;
            }

            Path filePath = Paths.get(baseDir, relativePath);

            if ("Create File".equals(action)) {
                try {
                    Files.createDirectories(filePath.getParent());
                    Files.writeString(filePath, content, StandardCharsets.UTF_8);
                    log.info("✅ Created: {}", filePath);
                } catch (IOException e) {
                    log.error("❌ Failed to write new file: {} - {}", filePath, e.getMessage());
                }
            } else if ("Modify File".equals(action)) {
                if (!Files.exists(filePath)) {
                    log.info("❌ Cannot modify file that does not exist: {}. Treating as a new file.", filePath);
                     try {
                        Files.createDirectories(filePath.getParent());
                        Files.writeString(filePath, content, StandardCharsets.UTF_8);
                        log.info("✅ Created (as fallback): {}", filePath);
                    } catch (IOException e) {
                        log.error("❌ Failed to write fallback file: {} - {}", filePath, e.getMessage());
                    }
                    continue;
                }

                try {
                    String existingCode = Files.readString(filePath, StandardCharsets.UTF_8);
                    String newJavaCode = content;               

                    // Run the merge agent to combine existing code with the new snippet.
                    String mergedCode = filteredContent(codeMergeAgent.runCodeMergeAgent(existingCode, newJavaCode));
                    System.out.println("mergedCode: " + mergedCode);
                    Files.writeString(filePath, mergedCode, StandardCharsets.UTF_8); // Overwrite with merged content
                    log.info("✅ Merged and updated: {}", filePath);

            } catch (IOException e) {
                    log.error("❌ Failed to read or write modified file: {} - {}", filePath, e.getMessage());
                }
            } else if ("Refactored File".equals(action)) {
                try {
                    if (Files.exists(filePath)) {
                        Files.delete(filePath);
                        log.info("🗑️ Deleted existing file for refactoring: {}", filePath);
                    }
                    Files.createDirectories(filePath.getParent());
                    Files.writeString(filePath, content, StandardCharsets.UTF_8);
                    log.info("✅ Refactored and Created New File: {}", filePath);
                } catch (IOException e) {
                    log.error("❌ Failed to refactor/write file: {} - {}", filePath, e.getMessage());
                }
            }
        }
    }


    private String filteredContent(String rawContent) {
        // Always wrap rawContent in ```java ... ``` if not already present
        String content = "";

        // Extract content from markdown code blocks (e.g., ```java ... ```) if they exist.
        Pattern codeBlockPattern = Pattern.compile("```(?:java)?\\s*\\n(.*?)\\n```", Pattern.DOTALL);
        Matcher codeMatcher = codeBlockPattern.matcher(rawContent);

        if (codeMatcher.find()) {
            content = codeMatcher.group(1).trim();
        } else {
            content = rawContent; // Use raw content if no markdown block is found
        }
        return content.replace("```java", "").replace("```", "");
    }

    private void addPomXml(String baseDir, List<String> dependencies, ProjectConfig projectConfig) {
        StringBuilder dependenciesXml = new StringBuilder();
        List<String> managedDependencies = new ArrayList<>(dependencies);

        // --- Resilient Dependency Management ---
        // Ensure required starters are present without creating duplicates.
        addDependencyIfNotExists(managedDependencies, "org.springframework.boot:spring-boot-starter-validation");
        // For springdoc, we ENFORCE the version to avoid agent hallucinations.
        enforceDependency(managedDependencies, "org.springdoc:springdoc-openapi-starter-webmvc-ui:2.5.0");
        // Always ensure the test starter is present.
        addDependencyIfNotExists(managedDependencies, "org.springframework.boot:spring-boot-starter-test:test");


        for (String dep : managedDependencies) {
            String[] parts = dep.split(":");
            if (parts.length < 2) continue; // Skip invalid lines

                String groupId = parts[0].trim();
                String artifactId = parts[1].trim();
            String version = (parts.length > 2 && !parts[2].matches("compile|runtime|test|optional")) ? parts[2].trim() : null;
            String scope = (parts.length > 2 && version == null) ? parts[2].trim() :
                           (parts.length > 3) ? parts[3].trim() : null;

                dependenciesXml.append("        <dependency>\n");
                dependenciesXml.append(String.format("            <groupId>%s</groupId>\n", groupId));
                dependenciesXml.append(String.format("            <artifactId>%s</artifactId>\n", artifactId));

            if (version != null) {
                dependenciesXml.append(String.format("            <version>%s</version>\n", version));
            }

                if ("optional".equalsIgnoreCase(scope)) {
                    dependenciesXml.append("            <optional>true</optional>\n");
                } else if (scope != null && !scope.equalsIgnoreCase("compile")) {
                    dependenciesXml.append(String.format("            <scope>%s</scope>\n", scope));
                }
                dependenciesXml.append("        </dependency>\n");
        }

        String pom = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
            + "    xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n"
            + "    <modelVersion>4.0.0</modelVersion>\n"
            + "    <parent>\n"
            + "        <groupId>org.springframework.boot</groupId>\n"
            + "        <artifactId>spring-boot-starter-parent</artifactId>\n"
            + "        <version>" + projectConfig.getSpringBootVersion() + "</version>\n"
            + "        <relativePath/> <!-- lookup parent from repository -->\n"
            + "    </parent>\n"
            + "    <groupId>com.generated</groupId>\n"
            + "    <artifactId>microservice</artifactId>\n"
            + "    <version>1.0.0</version>\n"
            + "    <packaging>jar</packaging>\n"
            + "    <name>Generated Microservice</name>\n"
            + "    <properties>\n"
            + "        <java.version>" + projectConfig.getJavaVersion() + "</java.version>\n"
            + "    </properties>\n"
            + "    <dependencies>\n"
            + dependenciesXml.toString()
            + "        <!-- Logging dependencies for SLF4J with Logback -->\n"
            + "        <dependency>\n"
            + "            <groupId>org.slf4j</groupId>\n"
            + "            <artifactId>slf4j-api</artifactId>\n"
            + "        </dependency>\n"
            + "        <dependency>\n"
            + "            <groupId>ch.qos.logback</groupId>\n"
            + "            <artifactId>logback-classic</artifactId>\n"
            + "        </dependency>\n"
            + "    </dependencies>\n"
            + "    <build>\n"
            + "        <plugins>\n"
            + "            <plugin>\n"
            + "                <groupId>org.springframework.boot</groupId>\n"
            + "                <artifactId>spring-boot-maven-plugin</artifactId>\n"
            + "                <configuration>\n"
            + "                    <excludes>\n"
            + "                        <exclude>\n"
            + "                            <groupId>org.projectlombok</groupId>\n"
            + "                            <artifactId>lombok</artifactId>\n"
            + "                        </exclude>\n"
            + "                    </excludes>\n"
            + "                </configuration>\n"
            + "            </plugin>\n"
            + "        </plugins>\n"
            + "    </build>\n"
            + "\n"
            + "    <repositories>\n"
            + "        <repository>\n"
            + "            <id>maven-central</id>\n"
            + "            <url>https://repo.maven.apache.org/maven2</url>\n"
            + "        </repository>\n"
            + "        <repository>\n"
            + "            <id>atlassian-public</id>\n"
            + "            <url>https://packages.atlassian.com/maven/repository/public</url>\n"
            + "        </repository>\n"
            + "    </repositories>\n"
            + "\n"
            + "</project>\n";

        try {
            Files.writeString(Paths.get(baseDir, "pom.xml"), pom);
            log.info("✅ Created: pom.xml");
        } catch (IOException e) {
            log.error("❌ Failed to write pom.xml: {}", e.getMessage());
        }
    }


    private static void addDependencyIfNotExists(List<String> dependencies, String newDependency) {
        String[] newDepParts = newDependency.split(":");
        String newGroupId = newDepParts[0];
        String newArtifactId = newDepParts[1];

        boolean exists = dependencies.stream().anyMatch(dep -> {
            String[] parts = dep.split(":");
            return parts.length >= 2 && parts[0].equals(newGroupId) && parts[1].equals(newArtifactId);
        });

        if (!exists) {
            dependencies.add(newDependency);
        }
    }

    private static void enforceDependency(List<String> dependencies, String dependencyToEnforce) {
        String[] parts = dependencyToEnforce.split(":");
        String groupId = parts[0];
        String artifactId = parts[1];

        // Remove any existing dependency with the same groupId and artifactId, regardless of version
        dependencies.removeIf(dep -> {
            String[] depParts = dep.split(":");
            return depParts.length >= 2 && depParts[0].equals(groupId) && depParts[1].equals(artifactId);
        });

        // Add the dependency with the correct, enforced version
        dependencies.add(dependencyToEnforce);
        log.info("🤖 Enforced known-good version for dependency: {}", dependencyToEnforce);
    }


    private static void appendContentWithMetadata(Path filePath, String content, String branchName) {
        try {
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            String timestamp = LocalDateTime.now().format(dtf);

            String header = String.format(
                "\n\n---\n**Date:** %s\n**Branch:** %s\n---\n\n",
                timestamp,
                branchName
            );

            String fullContent = header + content + "\n--- END ---\n";

            // Ensure parent directories exist
            Files.createDirectories(filePath.getParent());

            // Create file if it doesn't exist, then append.
            Files.writeString(filePath, fullContent, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.info("✅ Appended content with metadata to {}", filePath.getFileName());
        } catch (IOException e) {
            log.error("❌ Failed to append content to {}: {}", filePath.getFileName(), e.getMessage());
        }
    }

    public static void addApplicationYml(String baseDir) {
        String content = """
            server:
              port: 8080
            spring:
              application:
                name: generated-microservice
              datasource:
                url: jdbc:postgresql://localhost:5432/mydatabase
                username: ${DB_USERNAME:user}
                password: ${DB_PASSWORD:password}
                driver-class-name: org.postgresql.Driver
              jpa:
                hibernate:
                  ddl-auto: update
                show-sql: true
            """;
        Path resourcesDir = Paths.get(baseDir, "src", "main", "resources");
        try {
            Files.createDirectories(resourcesDir);
            Files.writeString(resourcesDir.resolve("application.yml"), content);
            log.info("✅ Created: application.yml");
        } catch (IOException e) {
            log.error("❌ Failed to write application.yml: {}", e.getMessage());
        }
    }


    public static void addGithubActionsCiConfig(String baseDir) {
        String ciYml = """
            name: Java CI with Maven, Test, and Docker

            on:
            push:
                branches: [ "main", "master", "feature/**" ]
            pull_request:
                branches: [ "main", "master", "feature/**" ]

            jobs:
            build:
                runs-on: ubuntu-latest

                steps:
                - name: Checkout code
                uses: actions/checkout@v3

                - name: Set up JDK 17
                uses: actions/setup-java@v3
                with:
                    java-version: '17'
                    distribution: 'temurin'

                - name: Cache Maven packages
                uses: actions/cache@v3
                with:
                    path: ~/.m2
                    key: ${{ runner.os }}-m2-${{ hashFiles('**/pom.xml') }}
                    restore-keys: ${{ runner.os }}-m2

                - name: Build and test with Maven
                run: mvn clean verify

                - name: Upload Test Results
                if: always()
                uses: actions/upload-artifact@v3
                with:
                    name: junit-results
                    path: target/surefire-reports

                - name: Upload Code Coverage Report
                if: always()
                uses: actions/upload-artifact@v3
                with:
                    name: jacoco-report
                    path: target/site/jacoco

                - name: Set up Docker Buildx
                uses: docker/setup-buildx-action@v3

                - name: Build Docker image
                run: docker build -t springboot-app:latest .
            """;

        Path workflowDir = Paths.get(baseDir, ".github", "workflows");
        Path ciFile = workflowDir.resolve("ci.yml");

        try {
            Files.createDirectories(workflowDir);
            Files.writeString(ciFile, ciYml);
            log.info("⚙️  GitHub Actions CI config added at: {}", ciFile);
        } catch (IOException e) {
            log.error("❌ Failed to write CI config: {}", e.getMessage());
        }
    }


}
