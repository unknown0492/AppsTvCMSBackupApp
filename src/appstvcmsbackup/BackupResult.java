package appstvcmsbackup;

import java.util.ArrayList;
import java.util.List;

/**
 * @description Outcome of one backup step (database, CMS files, or the run itself).
 *              This is what gets logged and reported to the cloud webservice.
 * @author Sohail
 */
public class BackupResult {

    /** Backup type: MySQL database dump */
    public static final String TYPE_DB = "db";
    /** Backup type: CMS (htdocs) folder zip */
    public static final String TYPE_CMS = "cms";
    /** Backup type: a run-level problem not tied to one backup (drive missing, config error) */
    public static final String TYPE_RUN = "run";

    /** Status: backup written and verified */
    public static final String STATUS_SUCCESS = "success";
    /** Status: backup written and verified, but something needs attention */
    public static final String STATUS_WARNING = "warning";
    /** Status: no valid backup was produced */
    public static final String STATUS_FAILED = "failed";
    /** Status: not needed this run (already done today / not due yet) */
    public static final String STATUS_SKIPPED = "skipped";

    // One of TYPE_* constants
    public final String type;
    // One of STATUS_* constants
    public String status = STATUS_FAILED;
    // Epoch millis when this step started
    public long startedAt = System.currentTimeMillis();
    // Epoch millis when this step finished
    public long finishedAt = 0;
    // Final backup file name on the drive (empty when failed)
    public String fileName = "";
    // Size of the final backup file in bytes
    public long sizeBytes = 0;
    // SHA-256 of the final backup file (hex)
    public String sha256 = "";
    // Number of tables (db) or files (cms) inside the backup
    public long itemCount = 0;
    // Free bytes left on the backup drive after this step
    public long driveFreeBytes = 0;
    // Human readable summary or error
    public String message = "";
    // Non-fatal issues found during the step
    public final List<String> warnings = new ArrayList<String>();

    /**
     * @description Creates an empty (failed by default) result for the given backup type
     * @param type One of TYPE_DB, TYPE_CMS, TYPE_RUN
     */
    public BackupResult( String type ) {
        this.type = type;
    }

    /**
     * @description Marks the result as failed with the given reason and stamps the finish time
     * @param reason Why it failed
     * @return this, for chaining
     */
    public BackupResult fail( String reason ) {
        this.status = STATUS_FAILED;
        this.message = reason;
        this.finishedAt = System.currentTimeMillis();
        return this;
    }

    /**
     * @description Marks the result successful (or warning, if any warnings were added) and stamps finish time
     * @param summary Short summary of what was backed up
     * @return this, for chaining
     */
    public BackupResult succeed( String summary ) {
        this.status = warnings.isEmpty() ? STATUS_SUCCESS : STATUS_WARNING;
        StringBuilder sb = new StringBuilder( summary ); // Summary plus any warnings
        for ( String w : warnings ) {
            sb.append( " | WARNING: " ).append( w );
        }
        this.message = sb.toString();
        this.finishedAt = System.currentTimeMillis();
        return this;
    }

    /**
     * @description Marks the result skipped with the given reason
     * @param reason Why it was skipped
     * @return this, for chaining
     */
    public BackupResult skip( String reason ) {
        this.status = STATUS_SKIPPED;
        this.message = reason;
        this.finishedAt = System.currentTimeMillis();
        return this;
    }

    /**
     * @description True when a verified backup file was produced (success or warning)
     * @return boolean
     */
    public boolean producedBackup() {
        return STATUS_SUCCESS.equals( status ) || STATUS_WARNING.equals( status );
    }

    @Override
    public String toString() {
        return "[" + type + "] " + status.toUpperCase() + " - " + message;
    }
}
