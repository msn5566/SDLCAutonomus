

---
**Date:** 2025-08-01 02:44:14
**Branch:** feature/AG-18_20250801024314
---

## 📝 Project Summary

```text
Commit-Summary: Feat: Map XML data to MongoDB based on CSV mapping.

Feature: XML data mapping and MongoDB document creation.
Input: source.xml, expected_target.xml, mapping_with_validation.csv
Output: A MongoDB document created from source.xml data based on the mapping_with_validation.csv mapping.
Constraints: MongoDB collection should be created if it doesn't exist. Data should be mapped according to mapping_with_validation.csv.
Logic: 
1. Read source.xml, expected_target.xml and mapping_with_validation.csv.
2. Create a POJO from source.xml and expected_target.xml.
3. Map data from source.xml to match the structure of expected_target.xml based on mapping_with_validation.csv.
4. Connect to MongoDB.
5. If the required collection doesn't exist, create it.
6. Create a document in the MongoDB collection using the mapped data from source.xml.
```

### 🛠️ Core Dependencies

- `org.springframework.boot:spring-boot-starter-web`
- `com.google.adk:google-adk:0.1.0`
- `com.google.adk:google-adk-dev:0.1.0`
- `com.google.genai:google-genai:1.8.0`
- `ch.qos.logback:logback-classic`
- `org.json:json:20231013`
- `org.springframework.boot:spring-boot-starter-test:test`
- `org.projectlombok:lombok:1.18.30:optional`
- `org.springframework.boot:spring-boot-starter-data-mongodb`
- `org.springframework.boot:spring-boot-starter-validation`
- `com.fasterxml.jackson.dataformat:jackson-dataformat-xml`
- `org.apache.commons:commons-csv:1.10.0`

--- END ---
