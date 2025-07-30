package com.msn.SDLCAutonomus.model;

import lombok.Data;

@Data
public class ExtractedConfig {
    private GitConfig gitConfig;
    private ProjectConfig projectConfig;

    public ExtractedConfig(GitConfig gitConfig, ProjectConfig projectConfig) {
        this.gitConfig = gitConfig;
        this.projectConfig = projectConfig;
    }

}
