package io.siggi.togethertunes.secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerSecretTest {
    private static final String CTX = "stream_outputs.rtmp_key_enc:1";

    @TempDir
    Path tmp;

    private static boolean posix(Path p) {
        return p.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    @Test
    void generatesFileOnFirstStartAndReusesIt() throws Exception {
        Path data = tmp.resolve("data/nested");
        ServerSecret.Loaded first = ServerSecret.load(data, Map.of());
        assertEquals(ServerSecret.Source.GENERATED, first.source());
        Path file = data.resolve("secret.key");
        assertEquals(file, first.file());
        assertTrue(Files.isRegularFile(file));
        assertTrue(first.warnings().isEmpty(), first.warnings().toString());
        if (posix(file)) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        }
        String text = Files.readString(file, StandardCharsets.US_ASCII).strip();
        assertEquals(32, Base64.getDecoder().decode(text).length);
        try (var files = Files.list(data)) {
            assertEquals(List.of(file), files.toList(), "no temp files left behind");
        }

        String enc = first.cipher().encrypt("key", CTX);
        ServerSecret.Loaded second = ServerSecret.load(data, Map.of());
        assertEquals(ServerSecret.Source.FILE, second.source());
        assertEquals("key", second.cipher().decrypt(enc, CTX));
        assertEquals(text, Files.readString(file, StandardCharsets.US_ASCII).strip(), "file unchanged");
    }

    @Test
    void differentInstallsGetDifferentKeys() throws Exception {
        String enc = ServerSecret.load(tmp.resolve("a"), Map.of()).cipher().encrypt("x", CTX);
        SecretCipher other = ServerSecret.load(tmp.resolve("b"), Map.of()).cipher();
        assertThrows(SecretDecryptionException.class, () -> other.decrypt(enc, CTX));
    }

    @Test
    void environmentWinsAndCreatesNoFile() throws Exception {
        String b64 = ServerSecret.generateBase64();
        ServerSecret.Loaded loaded = ServerSecret.load(tmp, Map.of(ServerSecret.ENV_VAR, b64));
        assertEquals(ServerSecret.Source.ENVIRONMENT, loaded.source());
        assertNull(loaded.file());
        assertFalse(Files.exists(tmp.resolve("secret.key")));

        // Same key in a file decrypts what the env key encrypted.
        Files.writeString(tmp.resolve("secret.key"), b64 + "\n");
        if (posix(tmp)) {
            Files.setPosixFilePermissions(tmp.resolve("secret.key"), PosixFilePermissions.fromString("rw-------"));
        }
        String enc = loaded.cipher().encrypt("v", CTX);
        assertEquals("v", ServerSecret.load(tmp, Map.of()).cipher().decrypt(enc, CTX));
    }

    @Test
    void environmentAcceptsUrlSafeUnpaddedAndWhitespace() throws Exception {
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) {
            key[i] = (byte) (0xfb + i); // produces '-' / '_' in URL-safe base64
        }
        String std = Base64.getEncoder().encodeToString(key);
        String url = Base64.getUrlEncoder().withoutPadding().encodeToString(key);
        SecretCipher a = ServerSecret.load(tmp, Map.of(ServerSecret.ENV_VAR, "  " + std + "\n")).cipher();
        SecretCipher b = ServerSecret.load(tmp, Map.of(ServerSecret.ENV_VAR, url)).cipher();
        assertEquals("v", b.decrypt(a.encrypt("v", CTX), CTX));
    }

    @Test
    void blankEnvironmentFallsBackToFile() throws Exception {
        ServerSecret.Loaded loaded = ServerSecret.load(tmp, Map.of(ServerSecret.ENV_VAR, "  "));
        assertEquals(ServerSecret.Source.GENERATED, loaded.source());
    }

    @Test
    void malformedEnvironmentIsAnError() {
        SecretKeyException e = assertThrows(SecretKeyException.class,
                () -> ServerSecret.load(tmp, Map.of(ServerSecret.ENV_VAR, "not base64!")));
        assertTrue(e.getMessage().contains(ServerSecret.ENV_VAR), e.getMessage());
        e = assertThrows(SecretKeyException.class, () -> ServerSecret.load(tmp,
                Map.of(ServerSecret.ENV_VAR, Base64.getEncoder().encodeToString(new byte[16]))));
        assertTrue(e.getMessage().contains("32 bytes"), e.getMessage());
        assertFalse(Files.exists(tmp.resolve("secret.key")), "a bad env key must not fall back to a file");
    }

    @Test
    void malformedFileIsAnErrorAndIsNotReplaced() throws Exception {
        Path file = tmp.resolve("secret.key");
        Files.writeString(file, "garbage");
        assertThrows(SecretKeyException.class, () -> ServerSecret.load(tmp, Map.of()));
        assertEquals("garbage", Files.readString(file));

        Files.writeString(file, "");
        assertThrows(SecretKeyException.class, () -> ServerSecret.load(tmp, Map.of()));
        assertEquals("", Files.readString(file));
    }

    @Test
    void directoryInPlaceOfFileIsAnError() throws Exception {
        Files.createDirectory(tmp.resolve("secret.key"));
        assertThrows(SecretKeyException.class, () -> ServerSecret.load(tmp, Map.of()));
    }

    @Test
    void warnsAboutPermissiveFile() throws Exception {
        Path file = tmp.resolve("secret.key");
        Files.writeString(file, ServerSecret.generateBase64());
        if (!posix(file)) {
            return;
        }
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        ServerSecret.Loaded loaded = ServerSecret.load(tmp, Map.of());
        assertEquals(ServerSecret.Source.FILE, loaded.source());
        assertEquals(1, loaded.warnings().size());
        assertTrue(loaded.warnings().get(0).contains("chmod 600"), loaded.warnings().get(0));
        assertFalse(loaded.warnings().get(0).contains(Files.readString(file).strip()), "never print the key");
    }

    @Test
    void concurrentFirstStartsAgreeOnOneKey() throws Exception {
        Path data = tmp.resolve("race");
        int n = 16;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Callable<ServerSecret.Loaded>> tasks = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                tasks.add(() -> ServerSecret.load(data, Map.of()));
            }
            List<SecretCipher> ciphers = new ArrayList<>();
            int generated = 0;
            for (Future<ServerSecret.Loaded> f : pool.invokeAll(tasks)) {
                ServerSecret.Loaded l = f.get();
                ciphers.add(l.cipher());
                if (l.source() == ServerSecret.Source.GENERATED) {
                    generated++;
                }
            }
            assertEquals(1, generated);
            String enc = ciphers.get(0).encrypt("v", CTX);
            for (SecretCipher c : ciphers) {
                assertEquals("v", c.decrypt(enc, CTX));
            }
        } finally {
            pool.shutdownNow();
        }
        try (var files = Files.list(data)) {
            assertEquals(1, files.count(), "no temp files left behind");
        }
    }
}
