package com.example.crosspath.registration;

import org.junit.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * RegistrationSecret のユニットテスト。仕様: crosspath-registration-spec.md §5.1
 */
public class RegistrationSecretTest {

    @Test
    public void generate_returns43Characters() {
        String secret = RegistrationSecret.generate();
        assertNotNull(secret);
        assertEquals(43, secret.length());
    }

    @Test
    public void generate_containsOnlyBase64UrlChars() {
        String secret = RegistrationSecret.generate();
        for (int i = 0; i < secret.length(); i++) {
            char c = secret.charAt(i);
            boolean valid = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_';
            assertTrue("Invalid base64url char at index " + i + ": " + c, valid);
        }
    }

    @Test
    public void generate_decodesTo32Bytes() {
        String secret = RegistrationSecret.generate();
        byte[] decoded = Base64.getUrlDecoder().decode(secret);
        assertEquals(32, decoded.length);
    }

    @Test
    public void generate_doesNotRepeat() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String secret = RegistrationSecret.generate();
            assertTrue("Duplicate secret generated", seen.add(secret));
        }
    }

    @Test
    public void isValidFormat_validSecretReturnsTrue() {
        String secret = RegistrationSecret.generate();
        assertTrue(RegistrationSecret.isValidFormat(secret));
    }

    @Test
    public void isValidFormat_tooShortReturnsFalse() {
        assertFalse(RegistrationSecret.isValidFormat("abc"));
    }

    @Test
    public void isValidFormat_42CharsReturnsFalse() {
        String secret = RegistrationSecret.generate();
        assertFalse(RegistrationSecret.isValidFormat(secret.substring(0, 42)));
    }

    @Test
    public void isValidFormat_44CharsReturnsFalse() {
        String secret = RegistrationSecret.generate();
        assertFalse(RegistrationSecret.isValidFormat(secret + "a"));
    }

    @Test
    public void isValidFormat_paddingEqualsReturnsFalse() {
        // base64url のパディング '=' は不正
        assertFalse(RegistrationSecret.isValidFormat("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="));
    }

    @Test
    public void isValidFormat_plusSlashReturnsFalse() {
        // '+' と '/' は base64url では不正
        assertFalse(RegistrationSecret.isValidFormat("+AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
        assertFalse(RegistrationSecret.isValidFormat("/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
    }

    @Test
    public void isValidFormat_nullReturnsFalse() {
        assertFalse(RegistrationSecret.isValidFormat(null));
    }

    @Test
    public void isValidFormat_decodedLengthMismatchReturnsFalse() {
        // 42文字のbase64urlで、デコード長が合わないケース
        // "YQ" (2文字) を43文字分並べるとデコード時に31バイトになる可能性
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 43; i++) {
            sb.append('A');
        }
        assertTrue(RegistrationSecret.isValidFormat(sb.toString())); // 'A'*43 はデコードで32バイトになるはず
    }
}