

---
**Date:** 2025-08-01 01:47:11
**Branch:** feature/AG-18_20250801014527
---

## 📝 Project Summary

Feature: Data transformation and MongoDB document creation from XML sources.
Input:
  - source.xml file containing source data.
  - expected_target.xml file defining the target data structure.
  - mapping_with_validation.csv file specifying data mappings between source and target.
Output:
  - A POJO (Plain Old Java Object) created from the source.xml and expected_target.xml files.
  - Data mapped from source.xml to expected_target.xml based on the mapping_with_validation.csv.
  - A MongoDB collection created if it doesn't exist.
  - A MongoDB document created with the data from source.xml in the specified collection.
Constraints:
  - The data mapping must adhere to the rules specified in mapping_with_validation.csv.
  - The MongoDB collection should be created only if it doesn't already exist.
Logic:
  1. Parse source.xml and expected_target.xml to create corresponding POJOs.
  2. Read the mapping_with_validation.csv file to determine data mappings.
  3. Transform data from the source POJO to the target POJO according to the mapping rules.
  4. Connect to the MongoDB database.
  5. Check if the specified collection exists. If not, create it.
  6. Create a document in the MongoDB collection using the data from the source POJO.

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
- `com.fasterxml.jackson.dataformat:jackson-dataformat-xml`

--- END ---
