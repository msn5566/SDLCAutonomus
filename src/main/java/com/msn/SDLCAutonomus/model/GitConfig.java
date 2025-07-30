package com.msn.SDLCAPI.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class GitConfig {

    private String repoUrl;
    private String baseBranch;
    private String repoPath;

}
