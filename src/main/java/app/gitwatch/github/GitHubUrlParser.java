package app.gitwatch.github;

import app.gitwatch.api.ApiCodes;
import app.gitwatch.api.ApiException;
import java.net.URI;

public final class GitHubUrlParser {

    public record Coordinates(String owner, String repo) {
    }

    private GitHubUrlParser() {
    }

    public static Coordinates parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid();
        }
        URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw invalid();
        }
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            throw invalid();
        }
        String host = uri.getHost();
        if (host == null) {
            throw invalid();
        }
        if (!"github.com".equalsIgnoreCase(host) && !"www.github.com".equalsIgnoreCase(host)) {
            throw invalid();
        }
        String path = uri.getPath();
        if (path == null) {
            throw invalid();
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - 4);
        }
        String[] parts = path.split("/");
        if (parts.length != 3 || !valid(parts[1]) || !valid(parts[2])) {
            throw invalid();
        }
        return new Coordinates(parts[1], parts[2]);
    }

    private static boolean valid(String name) {
        if (name == null || name.length() < 1 || name.length() > 100) {
            return false;
        }
        if (name.startsWith("-") || name.endsWith(".git")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '_'
                    || c == '.';
            if (!ok) {
                return false;
            }
        }
        return !".".equals(name) && !"..".equals(name);
    }

    private static ApiException invalid() {
        return new ApiException(400, ApiCodes.VALIDATION_ERROR, "Invalid repository URL");
    }
}
