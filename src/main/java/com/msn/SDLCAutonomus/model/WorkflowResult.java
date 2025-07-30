package com.msn.SDLCAPI.model;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

@Data
public class WorkflowResult {

    String commitMessage = "feat: Initial project scaffold by AI agent";
    String requirementsSummary = "";
    String codeAndTestOutput = "";
    final List<String> dependencyList = new ArrayList<>();

}
