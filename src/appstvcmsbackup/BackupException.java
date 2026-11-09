package appstvcmsbackup;

/**
 * @description A backup step failed for a known, human-readable reason.
 *              The message is written to the log and sent to the cloud report as-is,
 *              so it must explain what went wrong and, where possible, how to fix it.
 * @author Sohail
 */
public class BackupException extends Exception {

    // Serialization id (required by Exception)
    private static final long serialVersionUID = 1L;

    /**
     * @description Creates an exception with a readable message
     * @param message What went wrong
     */
    public BackupException( String message ) {
        super( message );
    }

    /**
     * @description Creates an exception with a readable message and the underlying cause
     * @param message What went wrong
     * @param cause   The original exception
     */
    public BackupException( String message, Throwable cause ) {
        super( message, cause );
    }
}
