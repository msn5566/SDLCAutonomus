package com.msn.SDLCAPI.agents;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.msn.SDLCAPI.model.ProjectConfig;
import com.msn.SDLCAPI.model.WorkflowResult;
import com.msn.SDLCAPI.service.UtilityService;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@AllArgsConstructor
public class MainWorkflowAgent {

    private static final String REQUIREMENTS_AGENT_NAME = "RequirementsAgent";
    private static final String DEPENDENCY_AGENT_NAME = "DependencyAgent";
    private static final String CODEGEN_AGENT_NAME = "CodeGenAgent";
    private static final String TESTGEN_AGENT_NAME = "TestGenAgent";;

    private static final String KEY_REQUIREMENTS = "requirements";
    private static final String KEY_DEPENDENCIES = "dependencies";
    private static final String KEY_CODE = "code";
    private static final String KEY_TEST = "test";
    
    // --- Constants for File Parsing and Naming ---
    private static final String DEPS_SEPARATOR = "---END-DEPS---";
    private static final String COMMIT_SUMMARY_PREFIX = "Commit-Summary: ";


    private final UtilityService utilityService;


    public WorkflowResult runMainWorkflow(String userInput, ProjectConfig projectConfig, Map<String, String> agentPrompts, List<String> existingPomDependencies) {
        final SequentialAgent workflow = buildWorkflow(projectConfig, agentPrompts, existingPomDependencies);
        final WorkflowResult workflowResult = new WorkflowResult();

        try {
            utilityService.retryWithBackoff(() -> {
                // Reset state variables inside the retry loop to ensure a clean slate for each attempt
                workflowResult.setCommitMessage( "feat: Initial project scaffold by AI agent");
                workflowResult.setRequirementsSummary("");
                workflowResult.setCodeAndTestOutput("");
                workflowResult.getDependencyList().clear();

                log.info("\n--- Running Main AI Workflow ---");
                InMemoryRunner runner = new InMemoryRunner(workflow);
                Session session = runner.sessionService().createSession(runner.appName(), "user").blockingGet();
                Content userMsg = Content.fromParts(Part.fromText(userInput));

                runner.runAsync(session.userId(), session.id(), userMsg).blockingForEach(ev -> {
                    String response = ev.stringifyContent();
                    if (!response.isBlank()) {
                        log.info("[{}]\n{}\n", ev.author(), response);

                        if (DEPENDENCY_AGENT_NAME.equals(ev.author())) {
                            String[] parts = response.trim().split("\s*" + DEPS_SEPARATOR + "\s*");
                            if (parts.length > 0) {
                                workflowResult.getDependencyList().addAll(java.util.Arrays.asList(parts[0].trim().split("\s*\r?\n\s*")));
                            }
                        } else if (REQUIREMENTS_AGENT_NAME.equals(ev.author())) {
                            String reqResponse = response.trim();
                            String[] lines = reqResponse.split("\r?\n", 2);
                            if (lines.length > 0 && lines[0].startsWith(COMMIT_SUMMARY_PREFIX)) {
                                workflowResult.setCommitMessage(lines[0].substring(COMMIT_SUMMARY_PREFIX.length()).trim());
                                if (lines.length > 1) {
                                    workflowResult.setRequirementsSummary(lines[1].trim());
                                }
                            } else {
                                workflowResult.setRequirementsSummary(reqResponse);
                            }
                        }

                        if (CODEGEN_AGENT_NAME.equals(ev.author()) || TESTGEN_AGENT_NAME.equals(ev.author())) {
                            String codeAndTestOutput = workflowResult.getCodeAndTestOutput() ;
                            workflowResult.setCodeAndTestOutput(codeAndTestOutput += response + "\n\n"); ;
                        }
                    }
                });
                return null;
            });
        } catch (Exception e) {
            log.error("❌ The main AI workflow failed after multiple retries. Aborting.", e);
            return null;
        }
        return workflowResult;
    }


    public static SequentialAgent buildWorkflow(ProjectConfig projectConfig, Map<String, String> agentPrompts, List<String> existingPomDependencies) {
        LlmAgent req = LlmAgent.builder()
                .name(REQUIREMENTS_AGENT_NAME)
                .description("Extracts structured functional requirements from a Jira user story.")
                .instruction("""
                        First, create a one-line summary of the following Jira user story, formatted as a conventional Git commit message. Prefix it with "Commit-Summary: ".
                        Then, on new lines, extract the structured requirements from the same user story.

                        The structured requirements format is:
                        Feature:
                        Input:
                        Output:
                        Constraints:
                        Logic:
                        """)
                .model("gemini-2.0-flash")
                .outputKey(KEY_REQUIREMENTS)
                .build();

        LlmAgent deps = LlmAgent.builder()
                .name(DEPENDENCY_AGENT_NAME)
                .description("Determines required dependency features from the requirements.")
                .instruction(String.format("""
                    Based on the following requirements, identify the necessary Maven dependencies for a project using Java %s and Spring Boot %s.
                    This version context is CRITICAL for selecting compatible dependency versions.

                    **EXISTING DEPENDENCIES:**
                    %s

                    You are an expert at merging Maven dependencies. Your task is to produce a FINAL, MERGED list of dependencies.
                    Provide ONLY a list of `groupId:artifactId[:version][:scope]` tuples, one per line.
                    You MUST:
                    - **INCLUDE ALL** dependencies from the `EXISTING DEPENDENCIES` section, unless a newer version is explicitly required by the new feature.
                    - **ADD ONLY** new dependencies that are strictly necessary to implement the `NEW FEATURE REQUIREMENTS`.
                    - **UPDATE** the version of an existing dependency ONLY if the `NEW FEATURE REQUIREMENTS` necessitate a newer, compatible version.
                    - For any dependency NOT managed by the specified Spring Boot parent POM (like `springdoc-openapi` or other third-party libraries), you MUST provide an explicit, recent version number that is compatible with Spring Boot %s. For dependencies managed by Spring Boot, you MUST omit the version so the parent POM can manage it.

                    Use 'compile' for standard dependencies, 'runtime' for runtime-only, and 'optional' for tools like Lombok. If scope is 'compile', you can omit it.

                    After the dependency list, you MUST add a separator line containing exactly "---END-DEPS---".
                    After the separator, you MUST repeat the original requirements text provided below, exactly and without modification.

                    Example output format (assuming 'existing-dep' is from EXISTING DEPENDENCIES and 'new-dep' is a new requirement):
                    org.springframework.boot:spring-boot-starter-web
                    com.example:existing-dep
                    com.new.feature:new-dep:1.0.0
                    org.springdoc:springdoc-openapi-starter-webmvc-ui:2.5.0
                    ---END-DEPS---
                    Feature: User Management API
                    ...
                    Requirements:
                    {requirements}
                    """, projectConfig.getJavaVersion(), projectConfig.getSpringBootVersion(), String.join("\n", existingPomDependencies), projectConfig.getSpringBootVersion()))
                .model("gemini-2.0-flash")
                .outputKey(KEY_DEPENDENCIES)
                .build();

        LlmAgent code = LlmAgent.builder()
                .name(CODEGEN_AGENT_NAME)
                .description("Generates a complete Spring Boot microservice skeleton based on structured requirements.")
                .instruction(agentPrompts.get(CODEGEN_AGENT_NAME))
                .model("gemini-2.0-flash")
                .outputKey(KEY_CODE)
                .build();

        LlmAgent test = LlmAgent.builder()
                .name(TESTGEN_AGENT_NAME)
                .description("Generates JUnit 5 test cases for a Spring Boot microservice.")
                .instruction(agentPrompts.get(TESTGEN_AGENT_NAME))
                .model("gemini-2.0-flash")
                .outputKey(KEY_TEST)
                .build();

        return SequentialAgent.builder()
                .name("FullSpringBootMicroserviceWorkflow")
                .subAgents(req, deps, code, test)
                .build();
    }

}
