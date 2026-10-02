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
    private final Payment payment = new Payment();
    private final Auth auth = new Auth();
    private final RateLimit rateLimit = new RateLimit();
    private final Jobs jobs = new Jobs();

    public Auth getAuth() { return auth; }
    public RateLimit getRateLimit() { return rateLimit; }
    public Jobs getJobs() { return jobs; }
    public Payment getPayment() { return payment; }
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
        /**
         * Send account emails (password reset) in the background, so the time a request takes never shows whether an
         * account exists. Only tests turn this off, to see the email at once.
         */
        private boolean async = true;

        public boolean isAsync() { return async; }
        public void setAsync(boolean async) { this.async = async; }
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

    public static class Auth {
        /** The customer app's page that completes a password reset; the one-time token is appended to it. */
        private String resetLinkBaseUrl = "http://localhost:5173/reset-password";
        private int resetTokenTtlMinutes = 30;

        public String getResetLinkBaseUrl() { return resetLinkBaseUrl; }
        public void setResetLinkBaseUrl(String resetLinkBaseUrl) { this.resetLinkBaseUrl = resetLinkBaseUrl; }
        public int getResetTokenTtlMinutes() { return resetTokenTtlMinutes; }
        public void setResetTokenTtlMinutes(int resetTokenTtlMinutes) { this.resetTokenTtlMinutes = resetTokenTtlMinutes; }
    }

    /**
     * How many attempts of each kind one caller may make in a window (the windows are fixed in code). Generous for a
     * person, tight for a script. Tests raise them so that a suite sharing one address is not throttled.
     */
    public static class RateLimit {
        private boolean enabled = true;
        /** Sign-in requests per address, per 10 minutes. */
        private int loginPerIp = 30;
        /** FAILED sign-ins for one email from one address, per 15 minutes. */
        private int loginFailuresPerAccount = 10;
        /** Staff sign-in requests per address, per 10 minutes. */
        private int staffLoginPerIp = 30;
        /** Registrations per address, per hour. */
        private int registerPerIp = 20;
        /** Password-reset requests per address, per hour. */
        private int forgotPerIp = 10;
        /** Password-reset emails for one email address, per hour. */
        private int forgotPerEmail = 3;
        /** Password-reset completions per address, per hour. */
        private int resetPerIp = 20;
        /** Wrong current passwords for one signed-in account, per 15 minutes. */
        private int passwordChangeFailures = 5;
        /** Recipient-link requests per address, per minute. */
        private int recipientPerIp = 120;
        /** Demo payments per address, per hour. */
        private int paymentPerIp = 30;
        /** Customer job runs started by hand ("Run now") per customer, per hour — each one sends an email. */
        private int jobRunsPerUser = 20;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getLoginPerIp() { return loginPerIp; }
        public void setLoginPerIp(int v) { this.loginPerIp = v; }
        public int getLoginFailuresPerAccount() { return loginFailuresPerAccount; }
        public void setLoginFailuresPerAccount(int v) { this.loginFailuresPerAccount = v; }
        public int getStaffLoginPerIp() { return staffLoginPerIp; }
        public void setStaffLoginPerIp(int v) { this.staffLoginPerIp = v; }
        public int getRegisterPerIp() { return registerPerIp; }
        public void setRegisterPerIp(int v) { this.registerPerIp = v; }
        public int getForgotPerIp() { return forgotPerIp; }
        public void setForgotPerIp(int v) { this.forgotPerIp = v; }
        public int getForgotPerEmail() { return forgotPerEmail; }
        public void setForgotPerEmail(int v) { this.forgotPerEmail = v; }
        public int getResetPerIp() { return resetPerIp; }
        public void setResetPerIp(int v) { this.resetPerIp = v; }
        public int getPasswordChangeFailures() { return passwordChangeFailures; }
        public void setPasswordChangeFailures(int v) { this.passwordChangeFailures = v; }
        public int getRecipientPerIp() { return recipientPerIp; }
        public void setRecipientPerIp(int v) { this.recipientPerIp = v; }
        public int getPaymentPerIp() { return paymentPerIp; }
        public void setPaymentPerIp(int v) { this.paymentPerIp = v; }
        public int getJobRunsPerUser() { return jobRunsPerUser; }
        public void setJobRunsPerUser(int v) { this.jobRunsPerUser = v; }
    }

    /** Customer jobs (reminders and the weekly summary) and the scheduler that runs them. */
    public static class Jobs {
        /** Whether this instance runs scheduled jobs at all. Tests turn it off and run jobs themselves. */
        private boolean schedulerEnabled = true;
        /** How often the scheduler looks for jobs that are due. */
        private int pollSeconds = 60;
        /** The shortest time a customer's schedule may leave between two runs: more often than this is refused. */
        private int minIntervalMinutes = 60;
        /** The timezone a customer's job starts in (they can change it); a Java zone id such as Asia/Kolkata or UTC. */
        private String defaultTimezone = "Asia/Kolkata";
        /** How many days before a subscription ends the support-side expiry job reminds its customer (staff can change it). */
        private int expiryWindowDays = 7;

        public int getExpiryWindowDays() { return expiryWindowDays; }
        public void setExpiryWindowDays(int expiryWindowDays) { this.expiryWindowDays = expiryWindowDays; }
        public boolean isSchedulerEnabled() { return schedulerEnabled; }
        public void setSchedulerEnabled(boolean schedulerEnabled) { this.schedulerEnabled = schedulerEnabled; }
        public int getPollSeconds() { return pollSeconds; }
        public void setPollSeconds(int pollSeconds) { this.pollSeconds = pollSeconds; }
        public int getMinIntervalMinutes() { return minIntervalMinutes; }
        public void setMinIntervalMinutes(int minIntervalMinutes) { this.minIntervalMinutes = minIntervalMinutes; }
        public String getDefaultTimezone() { return defaultTimezone; }
        public void setDefaultTimezone(String defaultTimezone) { this.defaultTimezone = defaultTimezone; }
    }

    public static class Payment {
        /**
         * The demo payment provider: lets anyone "pay" for a plan without any money changing hands. For
         * development and testing only — it must stay off wherever plans are worth something.
         */
        private boolean demoEnabled = false;
        /** How long a paid-for plan can wait to be applied to an account before the payment expires. */
        private int redeemTtlHours = 24;

        public boolean isDemoEnabled() { return demoEnabled; }
        public void setDemoEnabled(boolean demoEnabled) { this.demoEnabled = demoEnabled; }
        public int getRedeemTtlHours() { return redeemTtlHours; }
        public void setRedeemTtlHours(int redeemTtlHours) { this.redeemTtlHours = redeemTtlHours; }
    }

    public static class Cors {
        private List<String> allowedOrigins = List.of("http://localhost:5173");

        public List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }
    }
}
