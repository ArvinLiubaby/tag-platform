package com.tagplatform.config;

import com.tagplatform.common.crypto.AesCipher;
import com.tagplatform.common.crypto.PasswordHasher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 加解密组件装配。
 */
@Configuration
public class CryptoConfig {

    @Bean
    public AesCipher aesCipher(AppProperties props) {
        return new AesCipher(props.crypto().masterKey());
    }

    @Bean
    public PasswordHasher passwordHasher() {
        return new PasswordHasher();
    }
}
