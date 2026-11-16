package de.mindlib.sendIntent;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ShareFileLimitsTest {
    @Test
    public void acceptsExactFileAndBatchBoundaries() {
        assertTrue(ShareFileLimits.isWithinLimit(0, ShareFileLimits.MAX_FILE_BYTES, ShareFileLimits.MAX_FILE_BYTES));
        assertTrue(ShareFileLimits.isWithinLimit(ShareFileLimits.MAX_BATCH_BYTES - 1, 1, ShareFileLimits.MAX_BATCH_BYTES));
    }

    @Test
    public void rejectsValuesAboveLimits() {
        assertFalse(ShareFileLimits.isWithinLimit(0, ShareFileLimits.MAX_FILE_BYTES + 1, ShareFileLimits.MAX_FILE_BYTES));
        assertFalse(ShareFileLimits.isWithinLimit(ShareFileLimits.MAX_BATCH_BYTES, 1, ShareFileLimits.MAX_BATCH_BYTES));
    }

    @Test
    public void rejectsNegativeAndOverflowLikeInputs() {
        assertFalse(ShareFileLimits.isWithinLimit(-1, 1, ShareFileLimits.MAX_BATCH_BYTES));
        assertFalse(ShareFileLimits.isWithinLimit(0, -1, ShareFileLimits.MAX_BATCH_BYTES));
        assertFalse(ShareFileLimits.isWithinLimit(Long.MAX_VALUE, 1, Long.MAX_VALUE));
    }
}
