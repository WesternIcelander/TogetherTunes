package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.settings.SettingKey;
import io.siggi.togethertunes.settings.Settings;
import java.util.Objects;

/**
 * A server-wide feature switch ({@code docs/auth.md#server-settings}). Feature switches are
 * checked <b>before</b> permissions: a disabled feature is off for everyone, admins included,
 * until it is re-enabled ({@link Permissions}).
 *
 * <p>The guest policy switches ({@code guests.*}) are not here: they gate what guests may do, not
 * what a permission grants, and are checked by the guest code paths.
 */
public enum Feature {
    /** {@code ytdlp.enabled}: YouTube imports ({@link Permission#LIBRARY_YOUTUBE}). */
    YOUTUBE(Settings.YTDLP_ENABLED, "YouTube imports are disabled on this server"),
    /** {@code outputs.rtmp.enabled}: RTMP push outputs. */
    OUTPUT_RTMP(Settings.OUTPUTS_RTMP_ENABLED, "RTMP outputs are disabled on this server"),
    /** {@code outputs.ts.enabled}: MPEG-TS outputs. */
    OUTPUT_TS(Settings.OUTPUTS_TS_ENABLED, "MPEG-TS outputs are disabled on this server"),
    /** {@code outputs.hls.enabled}: HLS outputs. */
    OUTPUT_HLS(Settings.OUTPUTS_HLS_ENABLED, "HLS outputs are disabled on this server"),
    /** {@code overlay.enabled}: the QR overlay ({@link Permission#OUTPUT_OVERLAY_VIDEO}). */
    OVERLAY(Settings.OVERLAY_ENABLED, "The QR overlay is disabled on this server");

    private final SettingKey<Boolean> setting;
    private final String disabledMessage;

    Feature(SettingKey<Boolean> setting, String disabledMessage) {
        this.setting = Objects.requireNonNull(setting);
        this.disabledMessage = disabledMessage;
    }

    /** The boolean server setting that switches this feature. */
    public SettingKey<Boolean> setting() {
        return setting;
    }

    /** The message of the {@code 403 feature_disabled} error. */
    public String disabledMessage() {
        return disabledMessage;
    }
}
