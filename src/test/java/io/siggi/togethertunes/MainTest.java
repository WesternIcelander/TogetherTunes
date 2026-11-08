package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class MainTest {
    @Test
    void versionFallsBackToDevOutsideTheJar() {
        // Tests run from target/classes, which has no manifest.
        assertEquals("dev", Main.version());
    }

    @Test
    void siggiHttpIsOnTheClasspath() throws ClassNotFoundException {
        Class<?> server = Class.forName("io.siggi.http.HTTPServer");
        assertFalse(server.isInterface());
    }
}
