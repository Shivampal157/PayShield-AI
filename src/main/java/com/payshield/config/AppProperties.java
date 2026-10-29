package com.payshield.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "payshield")
public class AppProperties {

    private String environmentLabel = "local";
    private final Cors cors = new Cors();
    private final Security security = new Security();
    private final Risk risk = new Risk();
    private final OpenSearch opensearch = new OpenSearch();
    private final Ai ai = new Ai();
    private final Outbox outbox = new Outbox();

    public String getEnvironmentLabel() {
        return environmentLabel;
    }

    public void setEnvironmentLabel(String environmentLabel) {
        this.environmentLabel = environmentLabel;
    }

    public Cors getCors() {
        return cors;
    }

    public Security getSecurity() {
        return security;
    }

    public Risk getRisk() {
        return risk;
    }

    public OpenSearch getOpensearch() {
        return opensearch;
    }

    public Ai getAi() {
        return ai;
    }

    public Outbox getOutbox() {
        return outbox;
    }

    public static class Cors {
        private List<String> allowedOrigins = List.of("http://localhost:8080", "http://127.0.0.1:8080");

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }

    public static class Security {
        private String jwtSecret;
        private long accessTokenTtlSeconds = 900;
        private long refreshTokenTtlSeconds = 604800;
        private boolean cookieSecure = false;
        private int maxLoginAttemptsPerMinute = 10;
        private int accountLockoutThreshold = 5;
        private int accountLockoutMinutes = 15;
        private String bootstrapAdminEmail = "";
        private String bootstrapAdminPassword = "";
        private boolean demoSeedEnabled = false;

        public String getJwtSecret() {
            return jwtSecret;
        }

        public void setJwtSecret(String jwtSecret) {
            this.jwtSecret = jwtSecret;
        }

        public long getAccessTokenTtlSeconds() {
            return accessTokenTtlSeconds;
        }

        public void setAccessTokenTtlSeconds(long accessTokenTtlSeconds) {
            this.accessTokenTtlSeconds = accessTokenTtlSeconds;
        }

        public long getRefreshTokenTtlSeconds() {
            return refreshTokenTtlSeconds;
        }

        public void setRefreshTokenTtlSeconds(long refreshTokenTtlSeconds) {
            this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
        }

        public boolean isCookieSecure() {
            return cookieSecure;
        }

        public void setCookieSecure(boolean cookieSecure) {
            this.cookieSecure = cookieSecure;
        }

        public int getMaxLoginAttemptsPerMinute() {
            return maxLoginAttemptsPerMinute;
        }

        public void setMaxLoginAttemptsPerMinute(int maxLoginAttemptsPerMinute) {
            this.maxLoginAttemptsPerMinute = maxLoginAttemptsPerMinute;
        }

        public int getAccountLockoutThreshold() {
            return accountLockoutThreshold;
        }

        public void setAccountLockoutThreshold(int accountLockoutThreshold) {
            this.accountLockoutThreshold = accountLockoutThreshold;
        }

        public int getAccountLockoutMinutes() {
            return accountLockoutMinutes;
        }

        public void setAccountLockoutMinutes(int accountLockoutMinutes) {
            this.accountLockoutMinutes = accountLockoutMinutes;
        }

        public String getBootstrapAdminEmail() {
            return bootstrapAdminEmail;
        }

        public void setBootstrapAdminEmail(String bootstrapAdminEmail) {
            this.bootstrapAdminEmail = bootstrapAdminEmail;
        }

        public String getBootstrapAdminPassword() {
            return bootstrapAdminPassword;
        }

        public void setBootstrapAdminPassword(String bootstrapAdminPassword) {
            this.bootstrapAdminPassword = bootstrapAdminPassword;
        }

        public boolean isDemoSeedEnabled() {
            return demoSeedEnabled;
        }

        public void setDemoSeedEnabled(boolean demoSeedEnabled) {
            this.demoSeedEnabled = demoSeedEnabled;
        }
    }

    public static class Risk {
        private int velocityMaxAttempts = 5;
        private int velocityWindowSeconds = 60;
        private double flagThreshold = 0.62;
        private double declineThreshold = 0.84;
        private int ragCacheTtlHours = 6;

        public int getVelocityMaxAttempts() {
            return velocityMaxAttempts;
        }

        public void setVelocityMaxAttempts(int velocityMaxAttempts) {
            this.velocityMaxAttempts = velocityMaxAttempts;
        }

        public int getVelocityWindowSeconds() {
            return velocityWindowSeconds;
        }

        public void setVelocityWindowSeconds(int velocityWindowSeconds) {
            this.velocityWindowSeconds = velocityWindowSeconds;
        }

        public double getFlagThreshold() {
            return flagThreshold;
        }

        public void setFlagThreshold(double flagThreshold) {
            this.flagThreshold = flagThreshold;
        }

        public double getDeclineThreshold() {
            return declineThreshold;
        }

        public void setDeclineThreshold(double declineThreshold) {
            this.declineThreshold = declineThreshold;
        }

        public int getRagCacheTtlHours() {
            return ragCacheTtlHours;
        }

        public void setRagCacheTtlHours(int ragCacheTtlHours) {
            this.ragCacheTtlHours = ragCacheTtlHours;
        }
    }

    public static class OpenSearch {
        private boolean enabled = true;
        private String scheme = "http";
        private String host = "localhost";
        private int port = 9200;
        private String username = "";
        private String password = "";
        private String indexName = "fraud-behavior-vectors";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getScheme() {
            return scheme;
        }

        public void setScheme(String scheme) {
            this.scheme = scheme;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getIndexName() {
            return indexName;
        }

        public void setIndexName(String indexName) {
            this.indexName = indexName;
        }
    }

    public static class Ai {
        private String provider = "OFFLINE";
        private String apiKey = "";
        private String baseUrl = "https://api.openai.com/v1";
        private String model = "gpt-4o-mini";
        private int timeoutSeconds = 10;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    public static class Outbox {
        private long pollIntervalMs = 3000;
        private int maxAttempts = 3;

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }
    }
}
