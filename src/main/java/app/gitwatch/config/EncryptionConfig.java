package app.gitwatch.config;

import app.gitwatch.security.EncryptorHolder;
import app.gitwatch.security.SecretEncryptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EncryptionConfig {

    @Bean
    public SecretEncryptor secretEncryptor(AppProperties properties) {
        SecretEncryptor encryptor = SecretEncryptor.fromBase64(properties.getEncryptionKey());
        EncryptorHolder.set(encryptor);
        return encryptor;
    }
}
