package com.msn.SDLCAutonomus.service;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.awt.Desktop;

import org.springframework.stereotype.Service;

import com.msn.SDLCAutonomus.agents.ReviewAgent;
import com.msn.SDLCAutonomus.model.GitConfig;
import com.msn.SDLCAutonomus.model.ProjectConfig;
import com.msn.SDLCAutonomus.model.SrsData;
import com.msn.SDLCAutonomus.model.WorkflowResult;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@AllArgsConstructor
public class UtilityService {

    private static final String DEPENDENCY_AGENT_NAME = "DependencyAgent";
    private static final String CODEGEN_AGENT_NAME = "CodeGenAgent";
    private static final String TESTGEN_AGENT_NAME = "TestGenAgent";

    public <T> T retryWithBackoff(java.util.function.Supplier<T> action) {
        int maxRetries = 3;
        long delayMillis = 2000L; // Start with 2 seconds
        Exception lastException = null;

        for (int i = 0; i < maxRetries; i++) {
            try {
                return action.get();
            } catch (Exception e) {
                lastException = e;
                // Recursively check the cause chain for a retriable ServerException.
                if (isCausedByServerException(e)) {
                    if (i < maxRetries - 1) {
                        log.warn("Model request failed (attempt {}/{}) with a server error. Retrying in {} ms...", i + 1, maxRetries, delayMillis);
                        try {
                            Thread.sleep(delayMillis);
                        } catch (InterruptedException interruptedException) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Workflow interrupted during backoff wait.", interruptedException);
                        }
                        delayMillis *= 2; // Exponential backoff
                    }
                } else {
                    // Not a retriable server error, fail fast.
                    throw new RuntimeException("An unrecoverable error occurred", e); // Not a retriable server error, fail fast.
                }
            }
        }
        // If we've exited the loop, it means all retries failed.
        throw new RuntimeException("Model request failed after " + maxRetries + " attempts.", lastException);
    }


    private boolean isCausedByServerException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof com.google.genai.errors.ServerException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }


    public String createTempDir(String originalRepoName){
        // --- NEW: Resolve output directory to a temp folder outside the current project ---
    try {
        Path projectRootPath = Paths.get(".").toRealPath();
        Path parentPath = projectRootPath.getParent();
        if (parentPath == null) {
            log.error("❌ Cannot determine parent directory of the project. Aborting.");
            return null;
        }
        // Define and create the temp directory.
        Path tempDir = parentPath.resolve("temp");
        Files.createDirectories(tempDir);

        // The original repo path from SRS is just the directory name.
        // Resolve it against the temp directory to get the desired absolute path.
       // String originalRepoName = gitConfig.repoPath;
        Path absoluteRepoPath = tempDir.resolve(originalRepoName);
        //gitConfig.repoPath = absoluteRepoPath.toString();
        log.info("✅ Generated project will be created in: {}", absoluteRepoPath.toString());
        return absoluteRepoPath.toString();
    } catch (IOException e) {
        log.error("❌ Could not determine project's real path or create temp directory. Aborting.", e);
        return null;
    }
    // --- END NEW LOGIC ---
    }


    public void ensureRepositoryIsReady(String outputDir, String repoUrl, String baseBranch) throws IOException, InterruptedException {
        File dir = new File(outputDir);
        if (dir.exists()) {
            log.info("Repository directory exists. Resetting to a clean state from origin/{}.", baseBranch);
            runCommand(dir, "git", "fetch", "origin"); // Make sure remote refs are up-to-date
            runCommand(dir, "git", "checkout", baseBranch); // Switch to the branch
            runCommand(dir, "git", "reset", "--hard", "origin/" + baseBranch); // Hard reset to match remote
            runCommand(dir, "git", "clean", "-fdx"); // Remove all untracked files and directories
            log.info("✅ Repository is now in a pristine state matching origin/{}.", baseBranch);
        } else {
            log.info("Cloning repository from {}", repoUrl);
            // More efficient clone: only get the single-branch history needed for analysis.
            runCommand(new File("."), "git", "clone", "--branch", baseBranch, "--single-branch", repoUrl, outputDir);
        }
    }


    public String createFeatureBranch(String repoPath, String issueKey) throws IOException, InterruptedException {
        
        File dir = new File(repoPath);
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
        String timestamp = LocalDateTime.now().format(dtf);
        String featureBranch = "feature/" + issueKey + "_" + timestamp;

        log.info("Creating and checking out new feature branch: {}", featureBranch);
        runCommand(dir, "git", "checkout", "-b", featureBranch);
        return featureBranch;
    }

    public String getCurrentProjectFiles(String repoPath) {
        StringBuilder fileList = new StringBuilder();
        Path startPath = Paths.get(repoPath);
        try {
            Files.walk(startPath)
                .filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> {
                    // Make path relative to the repo root for clarity
                    Path relativePath = startPath.relativize(path);
                    fileList.append(relativePath.toString().replace('\\', '/')).append("\n");
                });
        } catch (IOException e) {
            log.error("❌ Could not read existing project files: {}", e.getMessage());
            return "Could not read project file structure.";
        }
        if (fileList.length() == 0) {
            return "No existing .java files found. This appears to be a new project.";
        }
        return fileList.toString();
    }


    public List<String> parseExistingDependenciesFromPom(String pomContent) {
        log.info("--- 🤖 Starting parseExistingDependenciesFromPom ---");
        log.debug("Parsing pomContent (first 500 chars):\n{}", pomContent.substring(0, Math.min(pomContent.length(), 500)));

        List<String> dependencies = new ArrayList<>();
        Pattern dependencyBlockPattern = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
        Matcher blockMatcher = dependencyBlockPattern.matcher(pomContent);

        Pattern groupIdPattern = Pattern.compile("<groupId>\s*(.*?)\s*</groupId>", Pattern.DOTALL);
        Pattern artifactIdPattern = Pattern.compile("<artifactId>\s*(.*?)\s*</artifactId>", Pattern.DOTALL);
        Pattern versionPattern = Pattern.compile("<version>\s*(.*?)\s*</version>", Pattern.DOTALL);
        Pattern scopePattern = Pattern.compile("<scope>\s*(.*?)\s*</scope>", Pattern.DOTALL);
        // Make optional pattern more flexible to just check for presence of the tag, not its content
        Pattern optionalPattern = Pattern.compile("<optional(?:\s*[^>]*?)?>.*?</optional>|<optional\s*/>", Pattern.DOTALL);

        while (blockMatcher.find()) {
            String dependencyXml = blockMatcher.group(1); // Content within <dependency>...</dependency>
            log.debug("Found dependency block:\n{}", dependencyXml);

            String groupId = null;
            String artifactId = null;
            String version = null;
            String scope = null;
            boolean optional = false;

            Matcher m;

            m = groupIdPattern.matcher(dependencyXml);
            if (m.find()) groupId = m.group(1).trim();
            log.debug("  - groupId: {}", groupId);

            m = artifactIdPattern.matcher(dependencyXml);
            if (m.find()) artifactId = m.group(1).trim();
            log.debug("  - artifactId: {}", artifactId);

            m = versionPattern.matcher(dependencyXml);
            if (m.find()) version = m.group(1).trim();
            log.debug("  - version: {}", version);

            m = scopePattern.matcher(dependencyXml);
            if (m.find()) scope = m.group(1).trim();
            log.debug("  - scope: {}", scope);

            // Check for optional tag presence
            optional = optionalPattern.matcher(dependencyXml).find();
            log.debug("  - optional: {}", optional);

            if (groupId != null && artifactId != null) {
                StringBuilder dep = new StringBuilder();
                dep.append(groupId).append(":").append(artifactId);
                if (version != null) {
                    dep.append(":").append(version);
                }
                if (scope != null && !scope.equalsIgnoreCase("compile")) {
                    dep.append(":").append(scope);
                }
                if (optional) {
                    dep.append(":optional");
                }
                dependencies.add(dep.toString());
                log.debug("  - Added dependency: {}", dep.toString());
            } else {
                log.warn("  - Skipping dependency block due to missing groupId or artifactId: {}", dependencyXml);
            }
        }
        log.info("--- ✅ Finished parseExistingDependenciesFromPom. Found {} dependencies. ---", dependencies.size());
        return dependencies;
    }

    public  Map<String, String> getAgentPrompts(SrsData srsData,String combinedContext, String existingFiles, List<String> existingPomDependencies){
    
        Map<String, String> agentPrompts = new HashMap<>();
        agentPrompts.put(CODEGEN_AGENT_NAME, String.format("""
                You are a specialist Java developer. Your task is to implement features in a Spring Boot project.

                **Primary Objective:** First, analyze the `EXISTING PROJECT FILES` list. If you do not see a main application class (one annotated with `@SpringBootApplication`), you MUST create it in the base package (`%s`). After ensuring the main class exists, proceed to implement the new feature.

                **EXISTING FILE CONTEXT:**
                %s

                **MASTER DIRECTIVE: Principle of Least Functionality**
                This is your most important instruction. You are FORBIDDEN from generating any code, methods, or endpoints that are not EXPLICITLY required by the new feature description.
                - **Example:** If the requirement is to "find an employee by name," you will ONLY generate the controller endpoint, service method, and repository method for that search. You are FORBIDDEN from creating `getAllEmployees`, `getEmployeeById`, `addEmployee`, `updateEmployee`, or `deleteEmployee`.
                - You must write the minimum amount of code to satisfy the requirement.
                - You MUST NOT generate any test classes (files ending in Test.java). Test generation is handled by a separate agent.

                **SERVICE LAYER DIRECTIVE:**
                - For any service class you create, you MUST define an interface (e.g., `EmployeeService`) and a corresponding implementation class (e.g., `EmployeeServiceImpl`).
                - All dependent classes (like controllers) MUST inject and use the interface, not the concrete implementation.

                **CRITICAL INSTRUCTIONS:**
                1.  **Analyze Existing Structure:** Review the list of existing files and the `EXISTING FILE CONTEXT` to understand the current project state.
                2.  **Dependency Method Generation:** When your new code needs to call a method on a dependency (like a Service or Repository), you MUST first check the `EXISTING FILE CONTEXT` to see if that method already exists.
                    - If the method **already exists**, simply call it.
                    - If the method **does not exist**, you MUST generate the new method in the appropriate file using a `// Modify File:` block, in addition to generating the code that calls it. This is critical for ensuring the project compiles. For example, if you need `employeeRepository.findByName(name)`, but it doesn't exist, you must add the method to the `EmployeeRepository` interface/class.
                3.  **Generate Code Snippets:**
                    - For **new files**, provide the complete content.
                    - For **existing files**, you MUST ONLY generate the new code snippet (e.g., a new method, a new DTO class within a file, a new field, a new endpoint). DO NOT output the entire file.
                4.  **Output Format:**
                    - For a **NEW file**, use the format with a full path from the project root. Example: `// Create File: src/main/java/com/yourcompany/service/UserService.java`
                    - For **MODIFYING an existing file**, use the format with a full path from the project root. Example: `// Modify File: src/main/java/com/yourcompany/service/UserService.java`
                5.  **Adhere to Project Standards:**
                    - Use the existing base package: `%s`.
                    - Follow the existing coding style and patterns (e.g., constructor injection).
                    - Use Java `%s`.
                    - All generated code MUST use `jakarta.validation` for validation, not `javax.validation`.
                    - For all injected dependencies (like Services and Repositories), declare the fields as `private final` and use constructor injection. Lombok's `@RequiredArgsConstructor` is preferred.
                    - If an update method is requested, you MUST first fetch the existing entity, update its fields, and then save the modified entity.

                **EXISTING PROJECT FILES:**
                %s

                **NEW FEATURE REQUIREMENTS:**
                {requirements}
                """,
                srsData.getProjectConfig().getPackageName(),
                combinedContext,
                srsData.getProjectConfig().getPackageName(),
                srsData.getProjectConfig().getJavaVersion(),
                existingFiles
                ));
                        agentPrompts.put(DEPENDENCY_AGENT_NAME, String.format("""
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
                """,
                    srsData.getProjectConfig().getJavaVersion(),
                    srsData.getProjectConfig().getSpringBootVersion(),
                    String.join("\n", existingPomDependencies),
                     srsData.getProjectConfig().getSpringBootVersion()
                ));
        agentPrompts.put(TESTGEN_AGENT_NAME, String.format("""
                You are a senior test engineer. Your task is to write high-quality JUnit 5 unit tests to verify that the provided Java code correctly implements the given feature requirements.

                **CRITICAL INSTRUCTIONS:**
                1.  **Analyze Existing Structure:** Review the list of existing files to understand the current project structure and conventions.
                2.  **Generate Code Snippets:**
                    - For **new files**, provide the complete content.
                    - For **existing files**, you MUST ONLY generate the new code snippet (e.g., a new test method). DO NOT output the entire file.
                3.  **Output Format:**
                    - For a **NEW file**, use the format with a full path from the project root. Example: `// Create File: src/test/java/com/yourcompany/service/UserServiceTest.java`
                    - For **MODIFYING an existing file**, use the format with a full path from the project root. Example: `// Modify File: src/test/java/com/yourcompany/service/UserServiceTest.java`

                **STRICT TESTING DIRECTIVES:**
                - For ALL test classes, you MUST use ONLY classic Mockito-based unit tests.
                - DO NOT use `@Autowired`, `@WebMvcTest`, `@DataMongoTest`, or `@SpringBootTest`.
                - All test classes MUST be annotated with `@ExtendWith(MockitoExtension.class)`.
                - All test classes SHOULD be annotated with `@MockitoSettings(strictness = Strictness.LENIENT)` to avoid unnecessary stubbing errors.
                - All dependencies MUST be mocked with `@Mock`.
                - The class under test MUST be instantiated with `@InjectMocks`.
                - If you need to test a controller with `MockMvc`, set it up manually in a `@BeforeEach` method using `MockMvcBuilders.standaloneSetup(...)`.

                **DO NOT USE THE FOLLOWING ANNOTATIONS IN ANY TEST CLASS:**
                - `@Autowired`
                - `@WebMvcTest`
                - `@DataMongoTest`
                - `@SpringBootTest`
                - `@MockBean`

                **EXISTING PROJECT FILES:**
                %s

                **FEATURE REQUIREMENTS:**
                {requirements}

                **CODE TO TEST:**
                {code}
                """,
                existingFiles
        ));
        return agentPrompts;
    }

    public void addGitignoreEntry(String repoPath, String entry) {
        Path gitignorePath = Paths.get(repoPath, ".gitignore");
        try {
            if (!Files.exists(gitignorePath)) {
                Files.createFile(gitignorePath);
                log.info("✅ Created .gitignore at: {}", gitignorePath);
            }
            List<String> lines = Files.readAllLines(gitignorePath);
            if (!lines.contains(entry)) {
                Files.write(gitignorePath, (entry + System.lineSeparator()).getBytes(), StandardOpenOption.APPEND);
                log.info("✅ Added '{}' to .gitignore in: {}", entry, gitignorePath);
            } else {
                log.info("Skipped '{}' as it already exists in .gitignore.", entry);
            }
        } catch (IOException e) {
            log.error("❌ Failed to add '{}' to .gitignore: {}", entry, e.getMessage());
        }
    }

    public String finalizeAndSubmit(GitConfig gitConfig, String featureBranch, String commitMessage) {
        commitAndPush(gitConfig.getRepoPath(), commitMessage, featureBranch);
        String prUrl = createPullRequest(gitConfig.getRepoPath(), gitConfig.getBaseBranch(), featureBranch, commitMessage);
        if (prUrl != null) {
            openInBrowser(prUrl);
        }
        return prUrl;
    }
    

    public void commitAndPush(String baseDir, String commitMessage, String branch) {
        try {
            File workingDir = new File(baseDir);

            // --- NEW: Delete target directory before commit ---
            try {
                Path targetDir = Paths.get(baseDir, "target");
                if (Files.exists(targetDir)) {
                    log.info("Deleting target directory before commit: {}", targetDir);
                    Files.walk(targetDir)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
                }
            } catch (IOException e) {
                log.warn("Could not delete target directory before commit: {}", e.getMessage());
            }
            // --- END NEW LOGIC ---

            log.info("Adding files to Git...");
            runCommand(workingDir, "git", "add", ".");

            log.info("Committing changes...");
            runCommand(workingDir, "git", "commit", "-m", commitMessage);

            log.info("Pushing changes to origin/{}", branch);
            runCommand(workingDir, "git", "push", "origin", branch);
            log.info("🚀 Project pushed to GitHub successfully.");

        } catch (Exception e) {
            log.error("❌ Git commit or push failed: {}", e.getMessage());
            log.error("  - Please check repository permissions and ensure the branch exists.");
        }
    }

    private String createPullRequest(String baseDir, String baseBranch, String featureBranch, String title) {
        log.info("🤖 Attempting to create a Pull Request...");
        try {
            File workingDir = new File(baseDir);
            String body = "Automated PR created by AI agent. Please review the changes.";
            String prUrl = runCommandWithOutput(workingDir, "gh", "pr", "create", "--base", baseBranch, "--head", featureBranch, "--title", title, "--body", body);
            log.info("✅ Successfully created Pull Request: {}", prUrl.trim());
            return prUrl.trim();
        } catch (IOException e) {
            if (e.getMessage().toLowerCase().contains("command not found") || e.getMessage().toLowerCase().contains("cannot run program")) {
                log.error("❌ Critical Error: The 'gh' (GitHub CLI) command is not installed or not in the system's PATH.");
                log.error("  - Please install it from https://cli.github.com/ to enable automatic Pull Request creation.");
            } else {
                log.error("❌ Failed to create Pull Request: {}", e.getMessage());
                log.error("  - Ensure you are authenticated with 'gh auth login'.");
                log.error("  - Ensure the repository remote is configured correctly and you have permissions.");
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("❌ PR creation was interrupted.");
            return null;
        }
    }

    private static void openInBrowser(String url) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(new URI(url));
            } catch (Exception e) {
                log.error("❌ Failed to open browser: {}", e.getMessage());
            }
        }
    }


    
    /**
     * Helper method to run external commands, checking for errors and handling process streams.
     * This centralizes process execution for maintainability and robustness.
     *
     * @param workingDir The directory to run the command in.
     * @param command The command and its arguments.
     * @throws IOException If the command fails with a non-zero exit code.
     * @throws InterruptedException If the thread is interrupted while waiting for the process.
     */
    public String runCommand(File workingDir, String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command).directory(workingDir);
        Process process = pb.start();

        // Capture stdout and stderr to prevent blocking and for better error reporting
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String combinedOutput = "--- STDOUT ---\n" + output + "\n\n--- STDERR ---\n" + error;
            // Print the error stream from the process for better debugging
            log.error("Command error output:\n{}", combinedOutput);
            throw new IOException("Command failed with exit code " + exitCode + ": " + String.join(" ", command) + "\n\n" + combinedOutput);
        }
        // Return standard output on success, though for inherited IO this will be empty
        return output;
    }
    private String runCommandWithOutput(File workingDir, String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command).directory(workingDir);
        Process process = pb.start();

        // Capture stdout and stderr to prevent blocking and for better error reporting
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            // Print the error stream from the process for better debugging
            log.error("Command error output:\n{}", error);
            throw new IOException("Command failed with exit code " + exitCode + ": " + String.join(" ", command));
        }
        // Return standard output on success
        return output;
    }

}
