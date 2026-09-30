package com.example.integration;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class UserLoginTest {

    @Test
    public void testPasswordMatching() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String rawPassword = "admin1!";
        String validHash = "$2a$10$//t6bqqX3LNU1AmuzNsk9uVpip/n9HHMQeEDVIpitzBUXV5cTUyE2";
        
        System.out.println("Valid hash matches 'admin1!': " + encoder.matches(rawPassword, validHash));
        assertTrue(encoder.matches(rawPassword, validHash));
    }
}
