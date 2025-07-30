package com.msn.SDLCAutonomus.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
public class ProjectConfig {

    private String javaVersion;
    private String springBootVersion;
    private String packageName;
    

    // public void setJavaVersion(String javaVersion) {
    //     this.javaVersion = (javaVersion != null && !javaVersion.isBlank()) ? javaVersion : "17";
    // }

    // public void setSpringBootVersion(String springBootVersion) {
    //     this.springBootVersion = (springBootVersion != null && !springBootVersion.isBlank()) ? springBootVersion : "3.5.3";
    // }

    // public void setPackageName(String packageName) {
    //     this.packageName = (packageName != null && !packageName.isBlank()) ? packageName : "com.generated.microservice";
    // }

    public ProjectConfig(String javaVersion, String springBootVersion, String packageName) {
        this.javaVersion = (javaVersion != null && !javaVersion.isBlank()) ? javaVersion : "17";
        this.springBootVersion = (springBootVersion != null && !springBootVersion.isBlank()) ? springBootVersion : "3.5.3";
        this.packageName = (packageName != null && !packageName.isBlank()) ? packageName : "com.generated.microservice";
    }

}
