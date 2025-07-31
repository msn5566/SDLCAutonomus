package com.msn.SDLCAutonomus.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
import com.msn.SDLCAutonomus.agents.XmlTransformerAgent;

import com.msn.SDLCAutonomus.model.ExtractedConfig;
import com.msn.SDLCAutonomus.model.GitConfig;
import com.msn.SDLCAutonomus.model.JiraConfig;
import com.msn.SDLCAutonomus.model.ProjectConfig;
import com.msn.SDLCAutonomus.model.SrsData;
import com.msn.SDLCAutonomus.model.WorkflowResult;
import com.msn.SDLCAutonomus.model.JiraAttachment;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
    private final XmlTransformerAgent xmlTransformerAgent;
    private final DataService dataService;

      // --- Constants for File System and Git ---
      private static final String AI_STATE_DIR = ".ai-state";
      private static final String JIRA_STATE_FILE_NAME = "jira_issue.txt";
      private static final String NO_CHANGES_DETECTED = "No changes detected.";
      

    public String runSDLCAuto(String jiraTicket) throws Exception {
        JiraConfig jiraConfig ;
        String userInput;
        ExtractedConfig extractedConfig;
        String featureBranch;
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


        GitConfig gitConfig = extractedConfig.getGitConfig();
        ProjectConfig projectConfig = extractedConfig.getProjectConfig();
        SrsData srsData = new SrsData(gitConfig, projectConfig, userInput);
        String absolutePath = utilityService.createTempDir(gitConfig.getRepoPath());


        if(absolutePath == null){
            return null;
        }else{
            gitConfig.setRepoPath(absolutePath);    
        }

        try {
            utilityService.ensureRepositoryIsReady(gitConfig.getRepoPath(), gitConfig.getRepoUrl(), gitConfig.getBaseBranch());
        } catch (Exception e) {
            log.error("❌ Failed to prepare the repository for analysis. Aborting. Error: {}", e.getMessage());
            throw e;
        }



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

    public String runXmlTransformationWorkflow(String sourceXmlContent, String mappingExcelContent, GitConfig gitConfig, ProjectConfig projectConfig) throws Exception {
        log.info("--- 🚀 Starting XML Transformation Workflow ---");

        // 1. Generate Source POJOs from Source XML
        log.info("Calling XmlPojoAgent to generate Source POJOs...");
        String sourcePojosJavaCode = xmlPojoAgent.runXmlPojoAgent(sourceXmlContent);
        if (sourcePojosJavaCode == null || sourcePojosJavaCode.isBlank()) {
            log.error("❌ XmlPojoAgent failed to generate Source POJOs. Aborting workflow.");
            return "XmlPojoAgent failed.";
        }
        writeClassesToFileSystemService.writeClassesToFileSystem(sourcePojosJavaCode, gitConfig.getRepoPath());
        log.info("✅ Source POJOs generated and written to file system.");

        // 2. Generate Mapping/Validation Logic from Excel and Source POJOs
        log.info("Calling ExcelAgent to generate Mapping Logic...");
        String mappingLogicJavaCode = excelAgent.runExcelAgent(sourcePojosJavaCode, mappingExcelContent);
        if (mappingLogicJavaCode == null || mappingLogicJavaCode.isBlank()) {
            log.error("❌ ExcelAgent failed to generate Mapping Logic. Aborting workflow.");
            return "ExcelAgent failed.";
        }
        // Mapping logic is a snippet, will be part of the final transformation class
        log.info("✅ Mapping Logic generated.");

        // 3. Assemble full Transformation Code (Target POJOs + Transformer Class)
        log.info("Calling XmlTransformerAgent to assemble final transformation code...");
        String finalTransformationCode = xmlTransformerAgent.runXmlTransformerAgent(sourceXmlContent, mappingExcelContent, sourcePojosJavaCode, mappingLogicJavaCode);
        if (finalTransformationCode == null || finalTransformationCode.isBlank()) {
            log.error("❌ XmlTransformerAgent failed to assemble final transformation code. Aborting workflow.");
            return "XmlTransformerAgent failed.";
        }
        writeClassesToFileSystemService.writeClassesToFileSystem(finalTransformationCode, gitConfig.getRepoPath());
        log.info("✅ Final Transformation Code generated and written to file system.");

        // --- Quality Gate: Verify the build before committing ---
        log.info("--- Quality Gate: Verifying build after XML transformation generation ---");
        String buildResult = verifyProjectBuild(gitConfig.getRepoPath());
        String prUrl = null;

        if (buildResult == null) {
            log.info("\n\n✅✅✅ Build Succeeded after XML transformation workflow! Proceeding to commit and create Pull Request...");
            // target directory deletion is already handled by UtilityService.commitAndPush
            // gitignore entry is already handled at the start of runSDLCAuto or by previous user action
            String commitMessage = "feat(xml-transform): Implement XML transformation logic";
            prUrl = utilityService.finalizeAndSubmit(gitConfig, "feature/xml-transform-" + UUID.randomUUID().toString().substring(0,8), commitMessage);
        } else {
            log.error("\n\n❌❌❌ Build Failed after XML transformation workflow. Self-healing process initiated...");
            String previousReviewAnalysis = "";
            int maxReviewRetries = 10;
            boolean buildSuccessAfterHealing = false;

            for (int i = 0; i < maxReviewRetries; i++) {
                log.error("\n\n❌❌❌ Build Failed on attempt {}. Starting self-healing process...", i + 1);
                String currentReviewAnalysis = reviewAgent.runReviewAgent(buildResult);
                
                if (currentReviewAnalysis.equals(previousReviewAnalysis) && i > 0) {
                    log.info("Review analysis is identical to previous one. Stopping self-healing attempts.");
                    break;
                }
                previousReviewAnalysis = currentReviewAnalysis;

                String allSourceCode = getAllSourceCodeForCorrection(gitConfig.getRepoPath());
                if (allSourceCode.isEmpty()) {
                    log.error("Could not find any source code to analyze for self-healing. Aborting.");
                    break;
                }
                String correctedCode = buildCorrectorAgent.runBuildCorrectorAgent(buildResult, currentReviewAnalysis, allSourceCode);

                if (correctedCode != null && !correctedCode.isBlank()) {
                    log.info("🤖 BuildCorrectorAgent provided a fix. Applying changes...");
                    writeClassesToFileSystemService.writeClassesToFileSystem(correctedCode, gitConfig.getRepoPath());

                    buildResult = verifyProjectBuild(gitConfig.getRepoPath());
                    if (buildResult == null) {
                        buildSuccessAfterHealing = true;
                        log.info("\n\n✅✅✅ Build Succeeded after self-healing! Proceeding to commit...");
                        prUrl = utilityService.finalizeAndSubmit(gitConfig, "feature/xml-transform-" + UUID.randomUUID().toString().substring(0,8), "fix(xml-transform): Self-healed build failure");
                        break;
                    }
                } else {
                    log.error("BuildCorrectorAgent failed to provide a fix. Aborting self-healing.");
                    break;
                }
            }
            if (!buildSuccessAfterHealing) {
                log.error("\n\n❌❌❌ Self-healing failed after {} attempts. Committing generated code with final failure analysis...", maxReviewRetries);
                String analysis = reviewAgent.runReviewAgent(buildResult);
                try {
                    Path analysisFile = Paths.get(gitConfig.getRepoPath(), "BUILD_FAILURE_ANALYSIS.md");
                    String fileContent = "# AI Build Failure Analysis\n\n" + "The AI-generated XML transformation code failed the build verification step. Here is the analysis from the Review Agent:\n\n" + "---\n\n" + analysis;
                    Files.writeString(analysisFile, fileContent);
                    log.info("✅ Wrote build failure analysis to {}", analysisFile.getFileName());
                } catch (IOException e) {
                    log.error("Failed to write build failure analysis file", e);
                }
                prUrl = utilityService.finalizeAndSubmit(gitConfig, "feature/xml-transform-" + UUID.randomUUID().toString().substring(0,8), "feat(xml-transform): Generated code with build issues - see BUILD_FAILURE_ANALYSIS.md");
            }
        }

        return prUrl != null ? "✅ XML Transformation Workflow completed successfully!\n\nPull Request URL: " + prUrl : "❌ XML Transformation Workflow failed. Check logs for details.";
    }

    /**
     * Runs XML transformation workflow using attachments from a Jira issue
     * @param jiraTicket The Jira issue key (e.g., "PROJ-123")
     * @param xmlAttachmentName The filename of the XML attachment to use as source
     * @param excelAttachmentName The filename of the Excel attachment to use for mapping
     * @return Result message with PR URL if successful
     */
    public String runXmlTransformationWorkflowFromJiraAttachments(String jiraTicket, String xmlAttachmentName, String excelAttachmentName) throws Exception {
        log.info("--- 🚀 Starting XML Transformation Workflow from Jira Attachments ---");
        log.info("Jira Ticket: {}", jiraTicket);
        log.info("XML Attachment: {}", xmlAttachmentName);
        log.info("Excel Attachment: {}", excelAttachmentName);

        // 1. Get Jira configuration and fetch attachments
        JiraConfig jiraConfig = configService.getJiraConfig(jiraTicket);
        List<JiraAttachment> attachments = configService.getJiraAttachments(jiraConfig);
        
        if (attachments.isEmpty()) {
            throw new IOException("No attachments found for Jira issue: " + jiraTicket);
        }

        // 2. Find the required attachments
        JiraAttachment xmlAttachment = null;
        JiraAttachment excelAttachment = null;

        for (JiraAttachment attachment : attachments) {
            if (attachment.getFilename().equalsIgnoreCase(xmlAttachmentName)) {
                xmlAttachment = attachment;
            }
            if (attachment.getFilename().equalsIgnoreCase(excelAttachmentName)) {
                excelAttachment = attachment;
            }
        }

        if (xmlAttachment == null) {
            throw new IOException("XML attachment not found: " + xmlAttachmentName + ". Available attachments: " + 
                attachments.stream().map(JiraAttachment::getFilename).collect(Collectors.joining(", ")));
        }

        if (excelAttachment == null) {
            throw new IOException("Excel attachment not found: " + excelAttachmentName + ". Available attachments: " + 
                attachments.stream().map(JiraAttachment::getFilename).collect(Collectors.joining(", ")));
        }

        // 3. Download attachment contents
        log.info("Downloading XML attachment: {}", xmlAttachment.getFilename());
        String sourceXmlContent = configService.downloadAttachmentAsString(jiraConfig, xmlAttachment.getContentUrl(), "UTF-8");
        
        log.info("Downloading Excel attachment: {}", excelAttachment.getFilename());
        String mappingExcelContent = configService.downloadAttachmentAsString(jiraConfig, excelAttachment.getContentUrl(), "UTF-8");

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
        return runXmlTransformationWorkflow(sourceXmlContent, mappingExcelContent, gitConfig, projectConfig);
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

}