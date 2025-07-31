# Jira Attachment XML Transformation Workflow

This document explains how to use the new functionality to download attachments from Jira and run the XML transformation workflow.

## Overview

The system now supports downloading XML and Excel attachments from Jira issues and using them to run the XML transformation workflow. This allows you to:

1. Attach XML source files and Excel mapping files to Jira issues
2. Automatically download these attachments
3. Run the complete XML transformation workflow using the downloaded content

## Prerequisites

### Environment Variables
Set the following environment variables for Jira authentication:

```bash
JIRA_URL=https://your-domain.atlassian.net
JIRA_EMAIL=your-email@domain.com
JIRA_API_TOKEN=your-api-token
```

### Jira Issue Configuration
Your Jira issue should contain:
- **Attachments**: XML source file and Excel mapping file
- **Description**: Repository configuration (Git URL, branch, etc.)

## API Endpoints

### 1. List Attachments
Get all attachments for a Jira issue:

```bash
GET /sdlc/auto/attachments/{jiraTicket}
```

**Example:**
```bash
curl "http://localhost:8080/sdlc/auto/attachments/PROJ-123"
```

**Response:**
```
Attachments for Jira issue PROJ-123:

📎 source-data.xml (2048 bytes, application/xml)
📎 mapping-rules.xlsx (1536 bytes, application/vnd.openxmlformats-officedocument.spreadsheetml.sheet)
📎 requirements.pdf (1024 bytes, application/pdf)
```

### 2. Run XML Transformation from Attachments
Execute the XML transformation workflow using Jira attachments:

```bash
GET /sdlc/auto/xml-transform/{jiraTicket}?xmlAttachment={xmlFilename}&excelAttachment={excelFilename}
```

**Example:**
```bash
curl "http://localhost:8080/sdlc/auto/xml-transform/PROJ-123?xmlAttachment=source-data.xml&excelAttachment=mapping-rules.xlsx"
```

## Workflow Process

When you call the XML transformation endpoint, the system will:

1. **Authenticate with Jira** using your configured credentials
2. **Fetch attachment metadata** for the specified Jira issue
3. **Download the XML attachment** and convert to string content
4. **Download the Excel attachment** and convert to string content
5. **Extract configuration** from the Jira issue description (Git repo, branch, etc.)
6. **Prepare the repository** by cloning and setting up the working directory
7. **Run the XML transformation workflow**:
   - Generate Source POJOs from XML
   - Generate Mapping Logic from Excel
   - Assemble final transformation code
   - Build and test the generated code
   - Create pull request if successful

## Example Usage

### Step 1: Create Jira Issue
Create a Jira issue with:
- **Summary**: "XML Transformation: Customer Data Mapping"
- **Description**: 
  ```
  Repository: https://github.com/your-org/data-transformation-service
  Base Branch: main
  Package: com.example.transformation
  Java Version: 21
  Spring Boot Version: 3.5.3
  ```
- **Attachments**: 
  - `customer-data.xml` (source XML file)
  - `mapping-rules.xlsx` (Excel mapping file)

### Step 2: List Attachments
```bash
curl "http://localhost:8080/sdlc/auto/attachments/PROJ-456"
```

### Step 3: Run Transformation
```bash
curl "http://localhost:8080/sdlc/auto/xml-transform/PROJ-456?xmlAttachment=customer-data.xml&excelAttachment=mapping-rules.xlsx"
```

## Error Handling

The system provides detailed error messages for common issues:

- **Missing Environment Variables**: Check JIRA_URL, JIRA_EMAIL, JIRA_API_TOKEN
- **Invalid Jira Credentials**: Verify your email and API token
- **Missing Attachments**: Ensure the specified files are attached to the Jira issue
- **Build Failures**: The system will attempt self-healing and provide analysis

## Security Considerations

- API tokens should have minimal required permissions
- Consider using environment-specific Jira instances
- Review generated code before merging pull requests
- Monitor attachment downloads for sensitive data

## Troubleshooting

### Common Issues

1. **"No attachments found"**
   - Verify the Jira issue has attachments
   - Check attachment permissions

2. **"XML attachment not found"**
   - Ensure exact filename match (case-insensitive)
   - Use the attachment listing endpoint to verify names

3. **"Build Failed"**
   - Check the generated BUILD_FAILURE_ANALYSIS.md file
   - Review logs for specific compilation errors

4. **"Authentication failed"**
   - Verify Jira credentials
   - Check API token permissions

### Logs
Monitor application logs for detailed workflow progress:
```
--- 🚀 Starting XML Transformation Workflow from Jira Attachments ---
Jira Ticket: PROJ-123
XML Attachment: source-data.xml
Excel Attachment: mapping-rules.xlsx
✅ Successfully fetched 3 attachments for issue: PROJ-123
Downloading XML attachment: source-data.xml
✅ Successfully downloaded attachment: 2048 bytes
Downloading Excel attachment: mapping-rules.xlsx
✅ Successfully downloaded attachment: 1536 bytes
--- 🚀 Starting XML Transformation Workflow ---
``` 