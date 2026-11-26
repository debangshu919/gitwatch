package app.gitwatch.api;

public final class ApiCodes {

    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String CONFLICT = "CONFLICT";
    public static final String UNSUPPORTED_CHANNEL = "UNSUPPORTED_CHANNEL";
    public static final String PRIVATE_REPO = "PRIVATE_REPO";
    public static final String NOT_PUBLIC = "NOT_PUBLIC";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String RATE_LIMIT_UNAVAILABLE = "RATE_LIMIT_UNAVAILABLE";
    public static final String OAUTH_NOT_CONFIGURED = "OAUTH_NOT_CONFIGURED";
    public static final String OAUTH_FAILED = "OAUTH_FAILED";
    public static final String DELIVERY_FAILED = "DELIVERY_FAILED";
    public static final String UPSTREAM_UNAVAILABLE = "UPSTREAM_UNAVAILABLE";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private ApiCodes() {
    }
}
