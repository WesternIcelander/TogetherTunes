package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataDirTest {
    @TempDir
    Path dir;

    @Test
    void createsTheRootAndEverySubdirectory() throws IOException {
        Path root = dir.resolve("nested").resolve("data");
        DataDir data = DataDir.create(root);

        assertEquals(root, data.root());
        assertEquals(root.resolve("db"), data.db());
        assertEquals(root.resolve("media"), data.media());
        assertEquals(root.resolve("cards"), data.cards());
        assertEquals(root.resolve("rooms"), data.rooms());
        assertEquals(root.resolve("tmp"), data.tmp());
        for (String name : List.of("db", "media", "cards", "rooms", "tmp")) {
            assertTrue(Files.isDirectory(root.resolve(name)), name);
        }
        assertEquals(DataDir.SUBDIRECTORIES.size() + 1, data.all().size());
    }

    @Test
    void keepsExistingContents() throws IOException {
        Path root = dir.resolve("data");
        DataDir.create(root);
        Path file = Files.writeString(root.resolve("media").resolve("keep.txt"), "x");
        Path secret = Files.writeString(root.resolve("secret.key"), "k");

        DataDir.create(root);

        assertEquals("x", Files.readString(file));
        assertEquals("k", Files.readString(secret));
    }

    @Test
    void relativeRootIsMadeAbsolute() {
        DataDir data = DataDir.of(Path.of("some", "..", "data"));
        assertTrue(data.root().isAbsolute());
        assertEquals("data", data.root().getFileName().toString());
        assertEquals(data.root().resolve("tmp"), data.tmp());
    }

    @Test
    void failsWhenASubdirectoryIsAFile() throws IOException {
        Path root = Files.createDirectories(dir.resolve("data"));
        Files.writeString(root.resolve("rooms"), "not a directory");

        IOException e = assertThrows(IOException.class, () -> DataDir.create(root));
        assertTrue(e.getMessage().contains("rooms"), e.getMessage());
        assertTrue(e.getMessage().contains("not a directory"), e.getMessage());
    }

    @Test
    void failsWhenTheRootIsAFile() throws IOException {
        Path root = Files.writeString(dir.resolve("data"), "not a directory");

        IOException e = assertThrows(IOException.class, () -> DataDir.create(root));
        assertTrue(e.getMessage().contains("not a directory"), e.getMessage());
    }

    @Test
    void followsASymlinkedSubdirectory() throws IOException {
        Path root = Files.createDirectories(dir.resolve("data"));
        Path elsewhere = Files.createDirectories(dir.resolve("big-disk"));
        try {
            Files.createSymbolicLink(root.resolve("media"), elsewhere);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symlinks not supported: " + e);
        }

        DataDir data = DataDir.create(root);
        assertTrue(Files.isDirectory(data.media()));
    }

    @Test
    void failsWhenASubdirectoryIsNotWritable() throws IOException {
        Path root = Files.createDirectories(dir.resolve("data"));
        assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"), "needs POSIX permissions");
        Path tmp = Files.createDirectory(root.resolve("tmp"));
        Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("r-x------"));
        try {
            assumeTrue(!Files.isWritable(tmp), "running as a user that ignores permissions (root?)");
            IOException e = assertThrows(IOException.class, () -> DataDir.create(root));
            assertTrue(e.getMessage().contains("not writable"), e.getMessage());
        } finally {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rwx------"));
        }
    }
}
