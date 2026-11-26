package app.gitwatch.security;

public final class EncryptorHolder {

    private static volatile SecretEncryptor encryptor;

    private EncryptorHolder() {
    }

    public static void set(SecretEncryptor value) {
        encryptor = value;
    }

    public static SecretEncryptor get() {
        SecretEncryptor current = encryptor;
        if (current == null) {
            throw new IllegalStateException("GITWATCH_ENCRYPTION_KEY is not configured");
        }
        return current;
    }
}
