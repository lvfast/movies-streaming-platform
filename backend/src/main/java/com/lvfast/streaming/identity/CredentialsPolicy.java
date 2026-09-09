package com.lvfast.streaming.identity;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class CredentialsPolicy {

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,32}$");

    public String validateAndNormalizeUsername(String username) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new IdentityValidationException(
                    "username must contain 3-32 letters, digits, or underscores");
        }
        return username.toLowerCase(Locale.ROOT);
    }

    public void validatePassword(String password) {
        boolean validLength = password != null && password.length() >= 12 && password.length() <= 128;
        boolean hasUpper = password != null && password.chars().anyMatch(Character::isUpperCase);
        boolean hasLower = password != null && password.chars().anyMatch(Character::isLowerCase);
        boolean hasDigit = password != null && password.chars().anyMatch(Character::isDigit);
        if (!validLength || !hasUpper || !hasLower || !hasDigit) {
            throw new IdentityValidationException(
                    "password must be 12-128 characters and include upper-case, lower-case, and a digit");
        }
    }
}
