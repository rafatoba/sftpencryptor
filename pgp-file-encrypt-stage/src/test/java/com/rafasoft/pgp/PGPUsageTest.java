package com.rafasoft.pgp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.samples.pgp.KeyManagementException;
import com.google.samples.pgp.KeyManager;
import com.google.samples.pgp.PgpEncryptor;
import com.google.samples.pgp.PgpKeyManager;

public class PGPUsageTest {
    private static final Path PUBLIC_KEYS_PATH = Paths.get("src/test/resources/myPublicPgpKey.asc");
    private static final Path SECRET_KEYS_PATH = Paths.get("src/test/resources/myPrivatePgpKey.asc");

    @BeforeAll
    static void addKeys() throws IOException, KeyManagementException {
        KeyManager keyManager = PgpKeyManager.getInstance();

        try (InputStream publicKeys = Files.newInputStream(PUBLIC_KEYS_PATH);
             InputStream secretKeys = Files.newInputStream(SECRET_KEYS_PATH)) {
            keyManager.addPublicKeys(publicKeys);
            keyManager.addSecretKeys(secretKeys, "afar1981@".toCharArray());
        }
    }
    
    @Test
    void testDecryption() throws Exception {
        PgpEncryptor encryptor = new PgpEncryptor(PgpKeyManager.getInstance());
        System.out.println(encryptor.encrypt("Hola caracola"));
    }

}
