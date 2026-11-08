package io.siggi.togethertunes.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.db.MigrationRunner.MigrationException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** File naming rules and loading migrations from directories and jars. */
class MigrationLoadingTest {
    @TempDir
    Path dir;

    @Test
    void parsesFileNames() {
        Migration m = Migration.of("0042_add_items_index.sql", "SELECT 1");
        assertEquals(42, m.version());
        assertEquals("add_items_index", m.name());
        assertEquals("0042_add_items_index.sql", m.fileName());
        assertEquals(64, m.checksum().length());
    }

    @Test
    void rejectsBadFileNames() {
        for (String name : List.of("1_short.sql", "0001-dash.sql", "0001_Upper.sql", "0001_.sql", "0001_x.txt",
                "0000_zero.sql", "notes.md", "0001_x__y.sql")) {
            assertThrows(IllegalArgumentException.class, () -> Migration.of(name, ""), name);
        }
    }

    @Test
    void loadsADirectorySortedAndIgnoresHiddenFiles() throws IOException {
        Path sqlite = Files.createDirectories(dir.resolve("sqlite"));
        Files.writeString(sqlite.resolve("0002_b.sql"), "B");
        Files.writeString(sqlite.resolve("0010_c.sql"), "C");
        Files.writeString(sqlite.resolve("0001_a.sql"), "A");
        Files.writeString(sqlite.resolve(".gitkeep"), "");
        List<Migration> loaded = MigrationRunner.loadDirectory(sqlite);
        assertEquals(List.of("0001_a.sql", "0002_b.sql", "0010_c.sql"), loaded.stream().map(Migration::fileName).toList());
        assertEquals("A", loaded.get(0).sql());
    }

    @Test
    void strayFileInTheDirectoryIsAnError() throws IOException {
        Files.writeString(dir.resolve("0001_a.sql"), "A");
        Files.writeString(dir.resolve("README.md"), "notes");
        MigrationException e = assertThrows(MigrationException.class, () -> MigrationRunner.loadDirectory(dir));
        assertTrue(e.getMessage().contains("README.md"), e.getMessage());
    }

    @Test
    void missingDirectoryMeansNoMigrations() {
        assertEquals(List.of(), MigrationRunner.loadDirectory(dir.resolve("nope")));
    }

    @Test
    void loadsFromClasspathDirectory() throws IOException {
        Files.createDirectories(dir.resolve("mig/postgres"));
        Files.writeString(dir.resolve("mig/postgres/0001_a.sql"), "A");
        Files.writeString(dir.resolve("mig/postgres/0002_b.sql"), "B");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {dir.toUri().toURL()}, null)) {
            List<Migration> loaded = MigrationRunner.loadClasspath(loader, "mig", PostgresDialect.INSTANCE);
            assertEquals(List.of(1, 2), loaded.stream().map(Migration::version).toList());
            assertEquals(List.of(), MigrationRunner.loadClasspath(loader, "mig", SqliteDialect.INSTANCE));
        }
    }

    @Test
    void loadsFromAJarWithoutDirectoryEntries() throws IOException {
        Path jar = dir.resolve("app.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, "mig/sqlite/0002_b.sql", "B");
            put(out, "mig/sqlite/0001_a.sql", "A");
            put(out, "mig/sqlite/.gitkeep", "");
            put(out, "mig/sqlite/nested/0003_c.sql", "C");
            put(out, "mig/postgres/0001_pg.sql", "P");
            put(out, MigrationRunner.class.getName().replace('.', '/') + ".class", "not really a class");
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
            List<Migration> loaded = MigrationRunner.loadClasspath(loader, "mig", SqliteDialect.INSTANCE);
            assertEquals(List.of("0001_a.sql", "0002_b.sql"), loaded.stream().map(Migration::fileName).toList());
            assertEquals("B", loaded.get(1).sql());
        }
    }

    @Test
    void applicationMigrationsExistForBothDialectsInPairs() {
        ClassLoader loader = getClass().getClassLoader();
        List<String> sqlite = MigrationRunner.loadClasspath(loader, MigrationRunner.CLASSPATH_ROOT, SqliteDialect.INSTANCE)
                .stream().map(Migration::fileName).toList();
        List<String> postgres = MigrationRunner.loadClasspath(loader, MigrationRunner.CLASSPATH_ROOT, PostgresDialect.INSTANCE)
                .stream().map(Migration::fileName).toList();
        assertEquals(sqlite, postgres, "every migration needs a SQLite and a PostgreSQL version with the same name");
    }

    private static void put(JarOutputStream out, String name, String content) throws IOException {
        out.putNextEntry(new JarEntry(name));
        OutputStream o = out;
        o.write(content.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
}
