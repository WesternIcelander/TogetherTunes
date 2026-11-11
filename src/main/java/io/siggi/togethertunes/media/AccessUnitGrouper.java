package io.siggi.togethertunes.media;

import java.util.ArrayList;
import java.util.List;

/**
 * Groups a stream of H.264 NAL units (from {@link AnnexBReader}) into access units (frames),
 * following the rules of H.264 7.4.1.2.3 as far as an x264 stream needs them
 * ({@code docs/overlay.md#reading-the-output-java}):
 *
 * <ul>
 *   <li>A coded slice (NAL type 1 or 5) whose {@code first_mb_in_slice} is 0 starts a new picture,
 *       so it begins a new access unit unless the current one has no slice yet (then the NAL units
 *       before it, e.g. AUD, SPS, PPS, SEI, belong to the same access unit).
 *       {@code first_mb_in_slice} is the slice header's first field, {@code ue(v)}, so it is 0
 *       exactly when the first bit after the NAL header byte is 1.</li>
 *   <li>An AUD, SPS, PPS, SEI or a NAL type 14..18 after a slice also begins a new access unit:
 *       they come before the first slice of a picture.</li>
 *   <li>Every other NAL unit (further slices of the same picture, end of sequence, filler data)
 *       belongs to the current access unit.</li>
 * </ul>
 *
 * <p>Not thread-safe.
 */
public final class AccessUnitGrouper {
    /** One access unit: its NAL units in stream order (shared arrays, not copied). */
    public record AccessUnit(List<byte[]> nals) {
        public AccessUnit {
            nals = List.copyOf(nals);
        }

        /** Whether it holds a coded slice (NAL type 1 or 5). */
        public boolean hasSlice() {
            for (byte[] nal : nals) {
                if (isSlice(type(nal))) {
                    return true;
                }
            }
            return false;
        }

        /** Whether it holds an IDR slice (NAL type 5). */
        public boolean isIdr() {
            for (byte[] nal : nals) {
                if (type(nal) == AnnexB.NAL_IDR) {
                    return true;
                }
            }
            return false;
        }
    }

    private final List<byte[]> current = new ArrayList<>();
    private boolean currentHasSlice;

    /** The NAL unit type (low 5 bits of the header byte). */
    public static int type(byte[] nal) {
        return nal[0] & 0x1F;
    }

    static boolean isSlice(int type) {
        return type == AnnexB.NAL_SLICE || type == AnnexB.NAL_IDR;
    }

    /** Whether a slice NAL's {@code first_mb_in_slice} is 0 (first bit after the header is 1). */
    public static boolean firstMbIsZero(byte[] nal) {
        return nal.length > 1 && (nal[1] & 0x80) != 0;
    }

    /** NAL types that, after a slice, begin the next access unit (besides a first slice). */
    private static boolean beginsAccessUnit(int type) {
        return type == AnnexB.NAL_SEI || type == AnnexB.NAL_SPS || type == AnnexB.NAL_PPS
                || type == AnnexB.NAL_AUD || (type >= 14 && type <= 18);
    }

    /**
     * Adds the next NAL unit.
     *
     * @return the access unit it completed, or null if the current one goes on
     * @throws IllegalArgumentException if {@code nal} is empty
     */
    public AccessUnit push(byte[] nal) {
        if (nal.length == 0) {
            throw new IllegalArgumentException("empty NAL unit");
        }
        int type = type(nal);
        AccessUnit done = null;
        if (currentHasSlice && ((isSlice(type) && firstMbIsZero(nal)) || beginsAccessUnit(type))) {
            done = new AccessUnit(current);
            current.clear();
            currentHasSlice = false;
        }
        current.add(nal);
        if (isSlice(type)) {
            currentHasSlice = true;
        }
        return done;
    }

    /**
     * Ends the stream: the last access unit, or null if nothing is pending. NAL units after the
     * last slice that never got a slice of their own come back as an access unit without a slice
     * ({@link AccessUnit#hasSlice()} false).
     */
    public AccessUnit finish() {
        if (current.isEmpty()) {
            return null;
        }
        AccessUnit done = new AccessUnit(current);
        current.clear();
        currentHasSlice = false;
        return done;
    }
}
