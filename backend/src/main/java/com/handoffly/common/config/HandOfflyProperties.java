package com.handoffly.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Strongly-typed configuration for the {@code handoffly.*} namespace.
 * Single source for tunable, environment-driven settings — no magic values in code.
 */
@ConfigurationProperties(prefix = "handoffly")
public class HandOfflyProperties {

    private final Jwt jwt = new Jwt();
    private final Recipient recipient = new Recipient();
    private final Mail mail = new Mail();
    private final Storage storage = new Storage();
    private final Cors cors = new Cors();
    private final Support support = new Support();

    public Jwt getJwt() { return jwt; }
    public Recipient getRecipient() { return recipient; }
    public Mail getMail() { return mail; }
    public Storage getStorage() { return storage; }
    public Cors getCors() { return cors; }
    public Support getSupport() { return support; }

    public static class Jwt {
        private String secret;
        private long expirationMinutes = 720;
        /** Support staff sessions are shorter than customer sessions. */
        private long staffExpirationMinutes = 480;
        private String issuer = "handoffly";

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public long getExpirationMinutes() { return expirationMinutes; }
        public void setExpirationMinutes(long expirationMinutes) { this.expirationMinutes = expirationMinutes; }
        public long getStaffExpirationMinutes() { return staffExpirationMinutes; }
        public void setStaffExpirationMinutes(long staffExpirationMinutes) { this.staffExpirationMinutes = staffExpirationMinutes; }
        public String getIssuer() { return issuer; }
        public void setIssuer(String issuer) { this.issuer = issuer; }
    }

    public static class Recipient {
        private String linkBaseUrl = "http://localhost:5173/r";
        private int tokenTtlDays = 30;

        public String getLinkBaseUrl() { return linkBaseUrl; }
        public void setLinkBaseUrl(String linkBaseUrl) { this.linkBaseUrl = linkBaseUrl; }
        public int getTokenTtlDays() { return tokenTtlDays; }
        public void setTokenTtlDays(int tokenTtlDays) { this.tokenTtlDays = tokenTtlDays; }
    }

    public static class Mail {
        private String provider = "logging";
        private String from = "no-reply@handoffly.local";
        /** Optional path to a custom Proof-of-Handoff email template; blank uses the built-in one. */
        private String pdfTemplateFile = "";

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }
        public String getPdfTemplateFile() { return pdfTemplateFile; }
        public void setPdfTemplateFile(String pdfTemplateFile) { this.pdfTemplateFile = pdfTemplateFile; }
    }

    public static class Storage {
        private String type = "local";
        private String localDir = "./storage-data";
        private long maxFileSizeBytes = 15_728_640L;
        private List<String> allowedContentTypes = List.of();

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getLocalDir() { return localDir; }
        public void setLocalDir(String localDir) { this.localDir = localDir; }
        public long getMaxFileSizeBytes() { return maxFileSizeBytes; }
        public void setMaxFileSizeBytes(long maxFileSizeBytes) { this.maxFileSizeBytes = maxFileSizeBytes; }
        public List<String> getAllowedContentTypes() { return allowedContentTypes; }
        public void setAllowedContentTypes(List<String> allowedContentTypes) {
            // tolerate whitespace/newlines from YAML multiline values
            this.allowedContentTypes = allowedContentTypes.stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
    }

    public static class Support {
        /** Where new-ticket notices go, and the general contact address shown on the public site. */
        private String mailbox = "support@handoffly.local";
        /** Direct-call number, revealed only to customers whose plan includes direct calls. Blank = none. */
        private String phone = "";
        private final Provision provision = new Provision();

        public Provision getProvision() { return provision; }
        public String getMailbox() { return mailbox; }
        public void setMailbox(String mailbox) { this.mailbox = mailbox; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
    }

    /**
     * How a support staff account is created: on startup, if an email and password are given and no staff
     * member with that email exists yet. Values come from the environment — nothing is built in.
     */
    public static class Provision {
        private String name = "";
        private String email = "";
        private String password = "";
        private String role = "ADMIN";

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
    }

    public static class Cors {
        private List<String> allowedOrigins = List.of("http://localhost:5173");

        public List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }
    }
}
