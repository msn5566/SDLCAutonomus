package com.msn.SDLCAutonomus.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.msn.SDLCAutonomus.agents.BuildCorrectorAgent;
import com.msn.SDLCAutonomus.agents.ChangeAnalysisAgent;
import com.msn.SDLCAutonomus.agents.ContextExtractionAgent;
import com.msn.SDLCAutonomus.agents.ExtractedConfigAgent;
import com.msn.SDLCAutonomus.agents.MainWorkflowAgent;
import com.msn.SDLCAutonomus.agents.ReviewAgent;
import com.msn.SDLCAutonomus.agents.XmlPojoAgent;
import com.msn.SDLCAutonomus.agents.ExcelAgent;
import com.msn.SDLCAutonomus.agents.JsonMappingAgent;
import com.msn.SDLCAutonomus.agents.XsdGeneratorAgent;

import com.msn.SDLCAutonomus.model.ExtractedConfig;
import com.msn.SDLCAutonomus.model.GitConfig;
import com.msn.SDLCAutonomus.model.JiraConfig;
import com.msn.SDLCAutonomus.model.ProjectConfig;
import com.msn.SDLCAutonomus.model.SrsData;
import com.msn.SDLCAutonomus.model.WorkflowResult;
import com.msn.SDLCAutonomus.model.JiraAttachment;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.xml.sax.SAXException;

@Service
@AllArgsConstructor
@Slf4j
public class SDLCAutoService {

    private final ConfigService configService;
    private final ExtractedConfigAgent extractedConfigAgent;
    private final UtilityService utilityService;
    private final ChangeAnalysisAgent changeAnalysisAgent;
    private final ContextExtractionAgent contextExtractionAgent;
    private final MainWorkflowAgent mainWorkflowAgent;
    private final WriteClassesToFileSystemService writeClassesToFileSystemService;
    private final ReviewAgent reviewAgent;
    private final BuildCorrectorAgent buildCorrectorAgent;
    private final XmlPojoAgent xmlPojoAgent;
    private final ExcelAgent excelAgent;
    private final JsonMappingAgent jsonMappingAgent;
    private final XsdGeneratorAgent xsdGeneratorAgent;

      // --- Constants for File System and Git ---
      private static final String AI_STATE_DIR = ".ai-state";
      private static final String JIRA_STATE_FILE_NAME = "jira_issue.txt";
      private static final String NO_CHANGES_DETECTED = "No changes detected.";

      JiraConfig jiraConfig ;
      String userInput;
      GitConfig gitConfig;
      ProjectConfig projectConfig;

    private void setAllRequiredConfig(String jiraTicket) throws Exception{
       
        ExtractedConfig extractedConfig;

        try {
            jiraConfig = configService.getJiraConfig(jiraTicket);;
        } catch (IOException e) {
            log.error("❌ Configuration error: {}", e.getMessage());
            log.error("  - Please set JIRA_URL, JIRA_EMAIL, and JIRA_API_TOKEN environment variables.");
            throw e;
        }

       
        try {
            userInput = configService.getJiraIssueContent(jiraConfig);
        } catch (Exception e) {
            log.error("❌ Failed to fetch Jira issue: {}. Please check your credentials, URL, and issue key.", e.getMessage());
            throw e;
        }
       
        try {
            extractedConfig = extractedConfigAgent.runConfigAgent(userInput);
        } catch (IOException e) {
            log.error("❌ Failed to read configuration from Jira issue description: {}", e.getMessage());
            throw e;
        }


        gitConfig = extractedConfig.getGitConfig();
        projectConfig = extractedConfig.getProjectConfig();
        String absolutePath = utilityService.createTempDir(gitConfig.getRepoPath());


        if(absolutePath == null){
            return;
        }else{
            gitConfig.setRepoPath(absolutePath);    
        }

        try {
            utilityService.ensureRepositoryIsReady(gitConfig.getRepoPath(), gitConfig.getRepoUrl(), gitConfig.getBaseBranch());
        } catch (Exception e) {
            log.error("❌ Failed to prepare the repository for analysis. Aborting. Error: {}", e.getMessage());
            throw e;
        }

    }
      

    public String runSDLCAuto(String jiraTicket) throws Exception {
        String featureBranch;
        String generatedPojoCode = null;
        String generatedTransformationCode = null;
        SrsData srsData = new SrsData(gitConfig, projectConfig, userInput);

        setAllRequiredConfig(jiraTicket);
        // --- NEW LOGIC: Check for POJO creation or XML transformation requirements ---
        String lowerCaseUserInput = userInput.toLowerCase();
        if (matchesPOJOMappingFromXML(lowerCaseUserInput,"pojo")) {
            log.info("Detected XML POJO creation requirement.");
            try {
                String xmlAttachmentName = extractAttachmentNameFromJiraContent(userInput, "xmlAttachment");
                String sourceXmlContent = getAttachmentContent(jiraConfig, xmlAttachmentName, "source.xml");
                if (sourceXmlContent != null) {
                    generatedPojoCode = xmlPojoAgent.runXmlPojoAgent(sourceXmlContent);
                    if (generatedPojoCode != null && !generatedPojoCode.isBlank()) {
                        // Write generated POJO code directly to file system
                        writeClassesToFileSystemService.writeClassesToFileSystem(generatedPojoCode, gitConfig.getRepoPath());
                        log.info("✅ XML POJOs generated by XmlPojoAgent. : "+generatedPojoCode);
                    } else {
                        log.warn("XmlPojoAgent returned no code.");
                    }
                } else {
                    log.warn("Could not retrieve XML content for POJO generation.");
                }
            } catch (Exception e) {
                log.error("Error during XML POJO creation: {}", e.getMessage());
            }
        } 
        
        if (matchesPOJOMappingFromXML(lowerCaseUserInput,"mapping")) {
            log.info("Detected XML Transformation requirement.");
            try {
                String xmlAttachmentName = extractAttachmentNameFromJiraContent(userInput, "xmlAttachment");
                String excelAttachmentName = extractAttachmentNameFromJiraContent(userInput, "excelAttachment");

                String sourceXmlContent = getAttachmentContent(jiraConfig, xmlAttachmentName, "source.xml");
                String mappingExcelContent = getAttachmentContent(jiraConfig, excelAttachmentName, "mapping.csv");
                
                if (sourceXmlContent != null && mappingExcelContent != null) {
                    // Call the dedicated XML transformation workflow method
                    // This method already handles POJO gen + Excel mapping + transformation
                    generatedTransformationCode = runXmlTransformationWorkflow(sourceXmlContent, mappingExcelContent, null, gitConfig, projectConfig);
                    if (generatedTransformationCode != null && !generatedTransformationCode.isBlank()) {
                        writeClassesToFileSystemService.writeClassesToFileSystem(generatedTransformationCode, gitConfig.getRepoPath());
                        log.info("✅ XML Transformation code generated by XmlTransformerAgent. : "+generatedTransformationCode);
                    } else {
                        log.warn("XmlTransformerAgent returned no code.");
                    }
                } else {
                    log.warn("Could not retrieve required XML or Excel content for transformation.");
                }
            } catch (Exception e) {
                log.error("Error during XML Transformation: {}", e.getMessage());
            }
        }else {
            System.out.println("No Mapping requirement detected.");
        }
        // --- END NEW LOGIC ---


          // Perform change analysis by comparing the new SRS with the last known version.
          String changeAnalysis = performChangeAnalysis(gitConfig.getRepoPath(), userInput);
          
         // If the analysis agent found no changes, skip the rest of the workflow.
        if (changeAnalysis.trim().equals(NO_CHANGES_DETECTED)) {
            log.info("\n✅ No functional changes detected in SRS. The local repository has been updated to the latest from the base branch, but no feature branch will be created.");
            // The changelog is not written because no feature branch is created.
            throw new DuplicateFormatFlagsException(null);
        }  
        
        
        // Since changes were detected, proceed with creating a feature branch.
        
        try {
            featureBranch = utilityService.createFeatureBranch(gitConfig.getRepoPath(), jiraConfig.getIssueKey());
        } catch (Exception e) {
            log.error("❌ Failed to create feature branch. Aborting. Error: {}", e.getMessage());
            throw e;
        }


         // Get the list of existing files to provide context to the agent.
         String existingFiles = utilityService.getCurrentProjectFiles(gitConfig.getRepoPath());

         // --- NEW: Context Extraction for ALL existing Java files ---
         String combinedContext = contextExtraction(gitConfig.getRepoPath());

        // --- NEW: Read existing pom.xml and parse dependencies for DependencyAgent ---
        List<String> existingPomDependencies = getDependencyContext(gitConfig.getRepoPath());

        Map<String, String> agentPrompts = utilityService.getAgentPrompts(srsData, combinedContext, existingFiles, existingPomDependencies);

        // Pass generated POJO/Transformation code via agentPrompts for MainWorkflowAgent to include in its output
        if (generatedPojoCode != null) {
            agentPrompts.put("generatedPojoCode", generatedPojoCode);
        }
        if (generatedTransformationCode != null) {
            agentPrompts.put("generatedTransformationCode", generatedTransformationCode);
        }

        final WorkflowResult workflowResult = mainWorkflowAgent.runMainWorkflow(userInput, srsData.getProjectConfig(), agentPrompts, existingPomDependencies);

        if (workflowResult == null) {
            log.error("Workflow execution failed. Could not generate project files. Aborting.");
            return null;
        }

        writeClassesToFileSystemService.generateProjectFiles(gitConfig.getRepoPath(), workflowResult, userInput, changeAnalysis, srsData.getProjectConfig(), featureBranch);    

        // --- Quality Gate: Verify the build before committing ---
        String buildResult = verifyProjectBuild(gitConfig.getRepoPath());
        String prUrl = null;
        
        if (buildResult == null) {
            // --- HAPPY PATH: Build Succeeded ---
            log.info("\n\n✅✅✅ Build Succeeded! Proceeding to commit and create Pull Request...");

            // --- NEW: Delete target directory before commit ---
            try {
                Path targetDir = Paths.get(gitConfig.getRepoPath(), "target");
                if (Files.exists(targetDir)) {
                    log.info("Deleting target directory before commit: {}", targetDir);
                    Files.walk(targetDir)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
                }
            } catch (IOException e) {
                log.warn("Could not delete target directory: {}", e.getMessage());
            }
            // --- END DELETE ---

            // --- NEW: Add target/ to .gitignore to prevent pushing build artifacts ---
            utilityService.addGitignoreEntry(gitConfig.getRepoPath(), "target/");
            // --- END NEW LOGIC ---

            prUrl = utilityService.finalizeAndSubmit(gitConfig, featureBranch, workflowResult.getCommitMessage());
        } else {
            // --- FAILURE PATH: Build Failed, attempting self-healing ---
            boolean buildSuccess = false;
            String previousReviewAnalysis = ""; // Initialize to an empty string
            int maxReviewRetries = 10; // Define max retries

            for (int i = 0; i < maxReviewRetries; i++) { // Loop up to maxReviewRetries
                log.error("\n\n❌❌❌ Build Failed on attempt {}. Starting self-healing process...", i + 1);
                String currentReviewAnalysis = reviewAgent.runReviewAgent(buildResult);
                
                if (currentReviewAnalysis.equals(previousReviewAnalysis) && i > 0) {
                    log.info("Review analysis is identical to previous one. Stopping self-healing attempts.");
                    break; // Stop if analysis hasn't changed (after the first attempt)
                }
                previousReviewAnalysis = currentReviewAnalysis; // Update for next iteration

                // String faultyFilePath = findFaultyFile(reviewAnalysis, gitConfig.repoPath);

                // --- NEW: Get all source code for the agent to analyze ---
                String allSourceCode = getAllSourceCodeForCorrection(gitConfig.getRepoPath());
                if (allSourceCode.isEmpty()) {
                    log.error("Could not find any source code to analyze for self-healing. Aborting.");
                    break;
                }
                String correctedCode = buildCorrectorAgent.runBuildCorrectorAgent(buildResult, currentReviewAnalysis, allSourceCode);

                if (correctedCode != null && !correctedCode.isBlank()) {
                    log.info("🤖 BuildCorrectorAgent provided a fix. Applying changes...");
                    // The writeClassesToFileSystem can handle create/modify based on the markers
                    writeClassesToFileSystemService.writeClassesToFileSystem(correctedCode, gitConfig.getRepoPath());

                    // Retry the build
                    buildResult = verifyProjectBuild(gitConfig.getRepoPath());
                    if (buildResult == null) {
                        buildSuccess = true;
                        log.info("\n\n✅✅✅ Build Succeeded after self-healing! Proceeding to commit...");
                        prUrl = utilityService.finalizeAndSubmit(gitConfig, featureBranch, workflowResult.getCommitMessage());
                        break; // Build succeeded, break loop
                    }
                } else {
                    log.error("BuildCorrectorAgent failed to provide a fix. Aborting self-healing.");
                    break;
                }
            }

            if (!buildSuccess) {
                log.error("\n\n❌❌❌ Self-healing failed. Committing generated code with final failure analysis...");
                String analysis = reviewAgent.runReviewAgent(buildResult); // Final analysis
                try {
                    Path analysisFile = Paths.get(gitConfig.getRepoPath(), "BUILD_FAILURE_ANALYSIS.md");
                    String fileContent = "# AI Build Failure Analysis\n\n"
                        + "The AI-generated code failed the build verification step. Here is the analysis from the Review Agent:\n\n"
                        + "---\n\n"
                        + analysis;
                    Files.writeString(analysisFile, fileContent);
                    log.info("✅ Wrote build failure analysis to {}", analysisFile.getFileName());
                } catch (IOException e) {
                    log.error("❌ Failed to write build failure analysis file.", e);
                }
                String failedCommitMessage = "fix(ai): [BUILD FAILED] " + workflowResult.getCommitMessage();
                utilityService.commitAndPush(gitConfig.getRepoPath(), failedCommitMessage, featureBranch);
            }
        }


    
        return prUrl == null ? gitConfig.getRepoPath() : prUrl;
    }

    private String performChangeAnalysis(String repoDir, String newSrs) {
        try {
            Path oldSrsPath = Paths.get(repoDir, AI_STATE_DIR, JIRA_STATE_FILE_NAME);
            String oldSrsContent = "";
            if (Files.exists(oldSrsPath)) {
                log.info("Found previous Jira issue state file for comparison.");
                oldSrsContent = Files.readString(oldSrsPath);
            } else {
                log.info("No previous Jira issue state file found. This will be an initial analysis.");
            }
            return changeAnalysisAgent.runChangeAnalysisAgent(oldSrsContent, newSrs);
        } catch (RuntimeException e) {
            log.warn("Could not perform change analysis after multiple retries: {}", e.getMessage());
            return "Change analysis failed to run: " + e.getMessage();
        } catch (Exception e) {
            log.warn("Could not perform change analysis: {}", e.getMessage());
            return "Change analysis failed to run: " + e.getMessage();
        }
    }


    private String contextExtraction(String repoPath){
        // --- NEW: Context Extraction for ALL existing Java files ---
        StringBuilder allContextSummaries = new StringBuilder();
        try {
            Path srcPath = Paths.get(repoPath, "src", "main", "java");
            if (Files.exists(srcPath)) {
                Files.walk(srcPath)
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                          //  if(path.toString().contains("Controller") || path.toString().contains("entity") || path.toString().contains("model")) {
                                String fileContent = Files.readString(path);
                                String contextSummary = contextExtractionAgent.runContextExtractionAgent(fileContent);
                                allContextSummaries.append("--- File: ").append(srcPath.relativize(path)).append(" ---\n");
                                allContextSummaries.append(contextSummary).append("\n\n");
                            //}
                        } catch (IOException e) {
                            log.warn("Could not read or process file for context: {}", path);
                        }
                    });
            }
        } catch (IOException e) {
            log.error("❌ Could not walk source tree for context extraction: {}", e.getMessage());
        }
        return allContextSummaries.toString();
        
        // --- END Context Extraction ---
    }

    private List<String> getDependencyContext(String repoPath){
         // --- NEW: Read existing pom.xml and parse dependencies for DependencyAgent ---
         List<String> existingPomDependencies = new ArrayList<>();
         Path pomFilePath = Paths.get(repoPath, "pom.xml");
         if (Files.exists(pomFilePath)) {
             try {
                 String pomContent = Files.readString(pomFilePath);
                 existingPomDependencies = utilityService.parseExistingDependenciesFromPom(pomContent);
                 log.info("Existing pom.xml content: {}", existingPomDependencies);
                 log.info("Found existing pom.xml with {} dependencies.", existingPomDependencies.size());
             } catch (IOException e) {
                 log.warn("Could not read or parse existing pom.xml for dependencies: {}", e.getMessage());
             }
         } else {
             log.info("No existing pom.xml found. DependencyAgent will start from a clean slate.");
         }

         return existingPomDependencies;
         // --- END NEW LOGIC ---
    }

    private String getAllSourceCodeForCorrection(String repoPath) {
        StringBuilder allCode = new StringBuilder();
        Path srcRoot = Paths.get(repoPath, "src");
        if (!Files.exists(srcRoot)) {
            log.warn("Source directory does not exist in {}. Cannot get code for correction.", repoPath);
            return "";
        }
        try {
            Files.walk(srcRoot)
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                            String content = Files.readString(path);
                            // Use a relative path from the repo root for the marker
                            String relativePath = Paths.get(repoPath).relativize(path).toString().replace('\\', '/');
                            allCode.append(String.format("--- FILE START: %s ---\n", relativePath));
                            allCode.append(content).append("\n");
                            allCode.append(String.format("--- FILE END: %s ---\n\n", relativePath));
                        } catch (IOException e) {
                            log.warn("Could not read source file {}: {}", path, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.error("Error walking source tree for self-healing: {}", e.getMessage());
        }
        return allCode.toString();
    }

    public String verifyProjectBuild(String repoName) {
        log.info("\n--- 🛡️  Running Build & Static Analysis Verification ---");
        log.info("Wait .... Manven Build is running ...");
        try {
            File workingDir = new File(repoName);
            // Using 'verify' phase runs compilation, tests
            utilityService.runCommand(workingDir, getMavenExecutable(), "clean", "verify");
            log.info("✅ Build successful. Code compiled, tests passed, and static analysis found no critical issues.");
            return null; // Return null on success
        } catch (IOException | InterruptedException e) {
            log.error("❌ BUILD FAILED! A critical issue was found.", e);
            log.error("  - The build failed, tests did not pass.");
            log.error("  - The faulty code will NOT be committed. Please review the logs above for details.");
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // --- NEW: Analyze the build failure ---
            String buildLog = e.getMessage(); // The exception message now contains the full log
            String analysis = reviewAgent.runReviewAgent(buildLog);
            log.error("🤖 Review Agent Analysis:\n---\n{}\n---", analysis);
            return buildLog; // Return the log on failure
        }
    }

    private static String getMavenExecutable() {
        return System.getProperty("os.name").toLowerCase().startsWith("windows") ? "mvn.cmd" : "mvn";
    }




    //================================================================================================================================

   
   
    public String runDynamicAgent(String agentType, String input1, String input2) {
        switch (agentType) {
            case "xml-pojo-gen":
                return xmlPojoAgent.runXmlPojoAgent(input1);
            case "excel-map":
                return excelAgent.runExcelAgent(input1, input2);
            // Removed xml-transform from here, use runXmlTransformationWorkflow for this complex workflow
            // case "xml-transform":
            //     return xmlTransformerAgent.runXmlTransformerAgent(input1, input2);
            // Add more cases for other agents as needed
            default:
                return "Unknown agent type: " + agentType;
        }
    }

    public String runXmlTransformationWorkflow(String sourceXmlContent, String mappingContent, String targetXmlContent, GitConfig gitConfig, ProjectConfig projectConfig) throws Exception {
        log.info("--- 🚀 Starting XML Transformation Workflow ---");

        // 1. Generate XSD from Source XML, Target XML, and JSON Mapping
        log.info("Calling XsdGeneratorAgent to generate XSD...");
        String rawXsdContent = xsdGeneratorAgent.runXsdGeneratorAgent(sourceXmlContent, targetXmlContent, mappingContent);
        log.debug("Raw generatedXsdContent from XsdGeneratorAgent:\n{}", rawXsdContent);
        if (rawXsdContent == null || rawXsdContent.isBlank()) {
            log.error("❌ XSDGeneratorAgent failed to produce XSD. Aborting workflow.");
            return "XSDGeneratorAgent failed.";
        }else{
             // Optionally, write the generated XSD to file system for inspection
            writeClassesToFileSystemService.writeClassesToFileSystem(rawXsdContent,gitConfig.getRepoPath());
            log.info("✅ XSD generated and written to file system.");
        }

        // Extract only the XML content to handle cases where the LLM adds extra text or whitespace
        //Pattern xmlPattern = Pattern.compile("(?s)<\\?xml.*?><[^>]+>.*?</[^>]+>", Pattern.DOTALL);
        Pattern xmlPattern = Pattern.compile("```(?:java|xml)?\\s*\\n(.*?)\\n```", Pattern.DOTALL);
        Matcher matcher = xmlPattern.matcher(rawXsdContent);
        String generatedXsdContent = "";
        if (matcher.find()) {
            generatedXsdContent = matcher.group(1).trim(); // Get the full matched XML including prolog and trim
            log.debug("Cleaned generatedXsdContent:\n{}", generatedXsdContent);
        } else {
            log.warn("Could not find XML declaration in generated XSD. Attempting to proceed with original content.");
        }


        // 2. Validate Source XML against the generated XSD
        try {
            // Trim whitespace from XML and XSD content before validation
            String trimmedSourceXmlContent = sourceXmlContent.trim();
            String trimmedGeneratedXsdContent = generatedXsdContent.replace("```", "").trim();
            utilityService.validateXmlWithXsd(trimmedSourceXmlContent, trimmedGeneratedXsdContent);
            log.info("✅ Source XML validated successfully against generated XSD.");
        } catch (SAXException | IOException e) {
            e.printStackTrace();
            log.error("❌ Source XML validation failed against generated XSD: {}", e.getMessage());
            return "XML Validation Failed: " + e.getMessage();
        }

        // 3. Generate full transformation code using JsonMappingAgent (now without redundant Java validation logic)
        log.info("Calling JsonMappingAgent to generate full transformation code...");
        String finalTransformationCode = jsonMappingAgent.runJsonMappingAgent(sourceXmlContent, mappingContent, targetXmlContent);
        
        if (finalTransformationCode == null || finalTransformationCode.isBlank()) {
            log.error("❌ JsonMappingAgent failed to produce full transformation code. Aborting workflow.");
            return "JsonMappingAgent failed.";
        }
        writeClassesToFileSystemService.writeClassesToFileSystem(finalTransformationCode, gitConfig.getRepoPath());
        log.info("✅ Final Transformation Code generated and written to file system.");

        // --- Quality Gate: Verify the build before committing ---
        // log.info("--- Quality Gate: Verifying build after XML transformation generation ---");
        // String buildResult = verifyProjectBuild(gitConfig.getRepoPath());
        // String prUrl = null;

        // if (buildResult == null) {
        //     log.info("\n\n✅✅✅ Build Succeeded after XML transformation workflow! Proceeding to commit and create Pull Request...");
        //     // target directory deletion is already handled by UtilityService.commitAndPush
        //     // gitignore entry is already handled at the start of runSDLCAuto or by previous user action
        //     String commitMessage = "feat(xml-transform): Implement XML transformation logic";
        //     prUrl = utilityService.finalizeAndSubmit(gitConfig, "feature/xml-transform-" + UUID.randomUUID().toString().substring(0,8), commitMessage);
        // } else {
        //     log.error("\n\n❌❌❌ Build Failed after XML transformation workflow. Self-healing process initiated...");
        //     String previousReviewAnalysis = "";
        //     int maxReviewRetries = 10;
        //     boolean buildSuccessAfterHealing = false;

        //     for (int i = 0; i < maxReviewRetries; i++) {
        //         log.error("\n\n❌❌❌ Build Failed on attempt {}. Starting self-healing process...", i + 1);
        //         String currentReviewAnalysis = reviewAgent.runReviewAgent(buildResult);
                
        //         if (currentReviewAnalysis.equals(previousReviewAnalysis) && i > 0) {
        //             log.info("Review analysis is identical to previous one. Stopping self-healing attempts.");
        //             break;
        //         }
        //         previousReviewAnalysis = currentReviewAnalysis;

        //         String allSourceCode = getAllSourceCodeForCorrection(gitConfig.getRepoPath());
        //         if (allSourceCode.isEmpty()) {
        //             log.error("Could not find any source code to analyze for self-healing. Aborting.");
        //             break;
        //         }
        //         String correctedCode = buildCorrectorAgent.runBuildCorrectorAgent(buildResult, currentReviewAnalysis, allSourceCode);

        //         if (correctedCode != null && !correctedCode.isBlank()) {
        //             log.info("🤖 BuildCorrectorAgent provided a fix. Applying changes...");
        //             writeClassesToFileSystemService.writeClassesToFileSystem(correctedCode, gitConfig.getRepoPath());

        //             buildResult = verifyProjectBuild(gitConfig.getRepoPath());
        //             if (buildResult == null) {
        //                 buildSuccessAfterHealing = true;
        //                 log.info("\n\n✅✅✅ Build Succeeded after self-healing! Proceeding to commit...");
        //                 prUrl = utilityService.finalizeAndSubmit(gitConfig, "feature/xml-transform-" + UUID.randomUUID().toString().substring(0,8), "fix(xml-transform): Self-healed build failure");
        //                 break;
        //             }
        //         } else {
        //             log.error("BuildCorrectorAgent failed to provide a fix. Aborting self-healing.");
        //             break;
        //         }
        //     }
        //     if (!buildSuccessAfterHealing) {
        //         log.error("\n\n❌❌❌ Self-healing failed after {} attempts. Committing generated code with final failure analysis...", maxReviewRetries);
        //         String analysis = reviewAgent.runReviewAgent(buildResult);
        //         try {
        //             Path analysisFile = Paths.get(gitConfig.getRepoPath(), "BUILD_FAILURE_ANALYSIS.md");
        //             String fileContent = "# AI Build Failure Analysis\n\n" + "The AI-generated XML transformation code failed the build verification step. Here is the analysis from the Review Agent:\n\n" + "---\n\n" + analysis;
        //             Files.writeString(analysisFile, fileContent);
        //             log.info("✅ Wrote build failure analysis to {}", analysisFile.getFileName());
        //         } catch (IOException e) {
        //             log.error("Failed to write build failure analysis file", e);
        //         }
        //         prUrl = utilityService.finalizeAndSubmit(gitConfig, "feature/xml-transform-" + UUID.randomUUID().toString().substring(0,8), "feat(xml-transform): Generated code with build issues - see BUILD_FAILURE_ANALYSIS.md");
        //     }
        // }

        return "";//prUrl != null ? "✅ XML Transformation Workflow completed successfully!\n\nPull Request URL: " + prUrl : "❌ XML Transformation Workflow failed. Check logs for details.";
    }

    /**
     * Runs XML transformation workflow using attachments from a Jira issue
     * @param jiraTicket The Jira issue key (e.g., "PROJ-123")
     * @param xmlAttachmentName The filename of the XML attachment to use as source
     * @param excelAttachmentName The filename of the Excel attachment to use for mapping
     * @return Result message with PR URL if successful
     */
    public String runXmlTransformationWorkflowFromJiraAttachments(String jiraTicket, String xmlAttachmentName, String dataMappingFile, String targetXmlAttachmentName) throws Exception {
        log.info("--- 🚀 Starting XML Transformation Workflow from Jira Attachments ---");
        log.info("Jira Ticket: {}", jiraTicket);
        log.info("XML Attachment: {}", xmlAttachmentName);
        log.info("JSON Attachment: {}", dataMappingFile);

        // 1. Get Jira configuration and fetch attachments
        JiraConfig jiraConfig = configService.getJiraConfig(jiraTicket);
        List<JiraAttachment> attachments = configService.getJiraAttachments(jiraConfig);
        
        if (attachments.isEmpty()) {
            throw new IOException("No attachments found for Jira issue: " + jiraTicket);
        }

        // 2. Find the required attachments
        JiraAttachment xmlAttachment = null;
        JiraAttachment excelAttachment = null;
        JiraAttachment targetXmlAttachment = null;

        for (JiraAttachment attachment : attachments) {
            if (attachment.getFilename().equalsIgnoreCase(xmlAttachmentName)) {
                xmlAttachment = attachment;
            }
            if (attachment.getFilename().equalsIgnoreCase(dataMappingFile)) {
                excelAttachment = attachment;
            }
            if (targetXmlAttachmentName != null && attachment.getFilename().equalsIgnoreCase(targetXmlAttachmentName)) {
                targetXmlAttachment = attachment;
            }
        }

        if (xmlAttachment == null) {
            throw new IOException("XML attachment not found: " + xmlAttachmentName + ". Available attachments: " + 
                attachments.stream().map(JiraAttachment::getFilename).collect(Collectors.joining(", ")));
        }

        if (excelAttachment == null && (dataMappingFile != null && !dataMappingFile.isBlank())) {
            throw new IOException("Excel attachment not found: " + dataMappingFile + ". Available attachments: " +
                attachments.stream().map(JiraAttachment::getFilename).collect(Collectors.joining(", ")));
        }

        if (targetXmlAttachment == null && (targetXmlAttachmentName != null && !targetXmlAttachmentName.isBlank())) {
             throw new IOException("Target XML attachment not found: " + targetXmlAttachmentName + ". Available attachments: " +
                attachments.stream().map(JiraAttachment::getFilename).collect(Collectors.joining(", ")));
        }

        // 3. Download attachment contents
        log.info("Downloading XML attachment: {}", xmlAttachment.getFilename());
        String sourceXmlContent = configService.downloadAttachmentAsString(jiraConfig, xmlAttachment.getContentUrl(), "UTF-8");
        
        String mappingContent = null;
        if (excelAttachment != null) {
            log.info("Downloading Excel attachment: {}", excelAttachment.getFilename());
            mappingContent = configService.downloadAttachmentAsString(jiraConfig, excelAttachment.getContentUrl(), "UTF-8");
        }
        
        if (targetXmlAttachmentName != null && targetXmlAttachmentName.toLowerCase().endsWith(".json")) {
            // Assuming a JSON mapping is provided via the targetXmlAttachmentName if it's a JSON file
            log.info("Downloading JSON mapping attachment: {}", targetXmlAttachment.getFilename());
            mappingContent = configService.downloadAttachmentAsString(jiraConfig, targetXmlAttachment.getContentUrl(), "UTF-8");
        }

        String targetXmlContent = null;
        if (targetXmlAttachment != null) {
            log.info("Downloading Target XML attachment: {}", targetXmlAttachment.getFilename());
            targetXmlContent = configService.downloadAttachmentAsString(jiraConfig, targetXmlAttachment.getContentUrl(), "UTF-8");
        }

        if (mappingContent == null) {
            throw new IOException("Neither Excel nor JSON mapping attachment found or specified.");
        }

        // 4. Extract configuration from Jira issue description
        String userInput = configService.getJiraIssueContent(jiraConfig);
        ExtractedConfig extractedConfig = extractedConfigAgent.runConfigAgent(userInput);
        
        GitConfig gitConfig = extractedConfig.getGitConfig();
        ProjectConfig projectConfig = extractedConfig.getProjectConfig();

        // 5. Prepare repository
        String absolutePath = utilityService.createTempDir(gitConfig.getRepoPath());
        if (absolutePath == null) {
            throw new IOException("Failed to create temporary directory for repository");
        }
        gitConfig.setRepoPath(absolutePath);

        utilityService.ensureRepositoryIsReady(gitConfig.getRepoPath(), gitConfig.getRepoUrl(), gitConfig.getBaseBranch());

        // 6. Run the XML transformation workflow
        return runXmlTransformationWorkflow(sourceXmlContent, mappingContent, targetXmlContent, gitConfig, projectConfig);
    }

    /**
     * Lists all attachments for a Jira issue
     * @param jiraTicket The Jira issue key (e.g., "PROJ-123")
     * @return JSON string with attachment information
     */
    public String listJiraAttachments(String jiraTicket) throws Exception {
        log.info("Listing attachments for Jira ticket: {}", jiraTicket);
        
        JiraConfig jiraConfig = configService.getJiraConfig(jiraTicket);
        List<JiraAttachment> attachments = configService.getJiraAttachments(jiraConfig);
        
        if (attachments.isEmpty()) {
            return "No attachments found for Jira issue: " + jiraTicket;
        }
        
        StringBuilder result = new StringBuilder();
        result.append("Attachments for Jira issue ").append(jiraTicket).append(":\n\n");
        
        for (JiraAttachment attachment : attachments) {
            result.append("📎 ").append(attachment.getFilename())
                  .append(" (").append(attachment.getSize()).append(" bytes, ")
                  .append(attachment.getMimeType()).append(")\n");
        }
        
        return result.toString();
    }

    private String extractAttachmentNameFromJiraContent(String jiraIssueContent, String type) {
        String lowerCaseContent = jiraIssueContent.toLowerCase();

        List<String> keywords = new ArrayList<>();
        String fileExtension = null;

        if ("xmlAttachment".equalsIgnoreCase(type)) {
            keywords.add("sourcefilename:");
            keywords.add("source:");
            fileExtension = ".xml";
        } else if ("excelAttachment".equalsIgnoreCase(type)) {
            keywords.add("mapping file name:");
            keywords.add("mapping data file:");
            keywords.add("mapping file:");
            fileExtension = ".xlsx"; // Assuming .xlsx for Excel files
        }

        for (String keyword : keywords) {
            int startIndex = lowerCaseContent.indexOf(keyword);
            if (startIndex != -1) {
                int endIndex = lowerCaseContent.indexOf("\n", startIndex);
                if (endIndex == -1) {
                    endIndex = lowerCaseContent.length();
                }
                String extractedName = jiraIssueContent.substring(startIndex + keyword.length(), endIndex).trim();
                log.debug("Extracted name using keyword '{}': {}", keyword, extractedName);
                return extractedName; // Return the first match found
            }
        }

        // Fallback: search for file extension if not explicitly named with a keyword
        if (fileExtension != null) {
            // This is a very basic fallback and might pick up any .xml/.xlsx
            // A more robust solution might involve AI agent for better parsing
            int startIndex = lowerCaseContent.indexOf(fileExtension);
            if (startIndex != -1) {
                // Try to find the word before the extension
                int fileNameStart = lowerCaseContent.lastIndexOf(" ", startIndex) + 1;
                if (fileNameStart < 0) fileNameStart = 0; // Handle case where it's at the beginning of the content
                int fileNameEnd = startIndex + fileExtension.length();
                String potentialFileName = jiraIssueContent.substring(fileNameStart, fileNameEnd).trim();
                log.debug("Extracted name using extension '{}': {}", fileExtension, potentialFileName);
                return potentialFileName; // Return the first occurrence of the extension
            }
        }

        return null;
    }

    private String getAttachmentContent(JiraConfig jiraConfig, String attachmentName, String defaultFileName) throws Exception {
        final String nameToUse;
        if (attachmentName == null || attachmentName.isBlank()) {
            log.warn("Attachment name not explicitly provided. Trying default: {}", defaultFileName);
            nameToUse = defaultFileName; // Fallback to a default if not explicitly mentioned
        } else {
            nameToUse = attachmentName;
        }

        List<JiraAttachment> attachments = configService.getJiraAttachments(jiraConfig);
        Optional<JiraAttachment> foundAttachment = attachments.stream()
            .filter(att -> att.getFilename().equalsIgnoreCase(nameToUse))
            .findFirst();

        if (foundAttachment.isPresent()) {
            log.info("Downloading attachment: {}", foundAttachment.get().getFilename());
            return configService.downloadAttachmentAsString(jiraConfig, foundAttachment.get().getContentUrl(), "UTF-8");
        } else {
            log.error("❌ Required attachment '{}' not found in Jira. Available: {}", nameToUse, 
                attachments.stream().map(JiraAttachment::getFilename).collect(Collectors.joining(", ")));
            return null;
        }
    }

    private boolean matchesPOJOMappingFromXML(String input, String type ) {
        return type.equalsIgnoreCase("pojo") ?
         Pattern.compile(
            ".*(create|generate|convert|parse|build|deserialize).*?(pojo|java class).*?(xml|xsd|schema).*",
            Pattern.CASE_INSENSITIVE
        ).matcher(input).find() : 

        Pattern.compile(
    //".*(transform|convert|map).*?(excel|csv|spreadsheet).*?(to).*?(xml).*?(mapping)?.*",
    ".*(transform|convert|map).*?(xml|csv|spreadsheet|excel).*",
            Pattern.CASE_INSENSITIVE)
            .matcher(input).find();
    }
    

}
