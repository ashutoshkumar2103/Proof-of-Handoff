package com.handoffly.documentcheck;

/** The file types a comparison can be exported as. */
public enum ComparisonExportFormat {
    PDF("application/pdf", "pdf"),
    CSV("text/csv; charset=UTF-8", "csv");

    private final String contentType;
    private final String extension;

    ComparisonExportFormat(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String contentType() { return contentType; }
    public String extension() { return extension; }
}
