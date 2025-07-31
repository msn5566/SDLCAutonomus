package com.msn.SDLCAutonomus.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class JiraAttachment {
    private String filename;
    private String contentUrl;
    private Long size;
    private String mimeType;
} 