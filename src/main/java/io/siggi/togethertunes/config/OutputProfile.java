package io.siggi.togethertunes.config;

/**
 * The server-wide output profile ({@code output.profile}). Every mezzanine, card and overlay
 * encode uses it, so changing it means re-ingesting the library.
 */
public enum OutputProfile {
    P720_30("720p30", 1280, 720, 30),
    P1080_30("1080p30", 1920, 1080, 30);

    public static final OutputProfile DEFAULT = P720_30;

    private final String id;
    private final int width;
    private final int height;
    private final int fps;

    OutputProfile(String id, int width, int height, int fps) {
        this.id = id;
        this.width = width;
        this.height = height;
        this.fps = fps;
    }

    /** The name used in the config file, e.g. {@code 720p30}. */
    public String id() {
        return id;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int fps() {
        return fps;
    }

    /** Looks up a profile by its config name, or returns null if there is none. */
    public static OutputProfile byId(String id) {
        for (OutputProfile profile : values()) {
            if (profile.id.equals(id)) {
                return profile;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return id;
    }
}
