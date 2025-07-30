package com.msn.SDLCAutonomus.model;

import lombok.Data;

@Data
public class SrsData {
    final GitConfig gitConfig;
    final ProjectConfig projectConfig;
    final String srsContent;

    public SrsData(GitConfig gitConfig, ProjectConfig projectConfig, String srsContent) {
        this.gitConfig = gitConfig;
        this.projectConfig = projectConfig;
        this.srsContent = srsContent;
    }

}
