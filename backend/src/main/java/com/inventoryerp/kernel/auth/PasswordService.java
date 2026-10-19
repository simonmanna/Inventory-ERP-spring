package com.inventoryerp.kernel.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/** bcrypt cost 10, compatible with existing bcryptjs {@code $2a$/$2b$} hashes. */
@Service
public class PasswordService {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    public String hash(String plain) {
        return encoder.encode(plain);
    }

    public boolean compare(String plain, String hashed) {
        if (plain == null || hashed == null) {
            return false;
        }
        try {
            return encoder.matches(plain, hashed);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
