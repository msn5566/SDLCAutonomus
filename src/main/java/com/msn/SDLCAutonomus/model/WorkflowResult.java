package com.msn.SDLCAutonomus.model;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class WorkflowResult {

    private String commitMessage;
    private String requirementsSummary;
    private String codeAndTestOutput;
    private List<String> dependencyList;

}
