package de.mindlib.sendIntent;

/** Shared bounds for native Android share ingestion. */
final class ShareFileLimits {
    static final long MAX_FILE_BYTES = 30L * 1024L * 1024L;
    static final long MAX_BATCH_BYTES = 50L * 1024L * 1024L;
    static final int MAX_BATCH_FILES = 10;

    private ShareFileLimits() {}

    static boolean isWithinLimit(long currentBytes, long nextBytes, long limitBytes) {
        return currentBytes >= 0 && nextBytes >= 0 && limitBytes >= 0
            && currentBytes <= limitBytes && nextBytes <= limitBytes - currentBytes;
    }
}
