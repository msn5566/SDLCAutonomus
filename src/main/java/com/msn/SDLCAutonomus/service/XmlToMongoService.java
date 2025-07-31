package com.msn.SDLCAutonomus.service;

import org.springframework.web.multipart.MultipartFile;

public interface XmlToMongoService {
    void mapXmlAndCreateDocument(MultipartFile sourceXml, MultipartFile targetXml, MultipartFile mappingCsv);
}