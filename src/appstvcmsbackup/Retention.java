package appstvcmsbackup;

import java.io.File;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @description Keeps the newest N verified backups and removes leftovers of interrupted runs.
 *              Rules that make it safe:
 *              1. It only runs AFTER a new backup has been verified.
 *              2. Only files matching the exact backup name pattern AND having a .sha256 file are counted.
 *              3. Anything else in the folder (desktop.ini, user files, ...) is never touched.
 * @author Sohail
 */
public final class Retention {

    /** Timestamp format used in backup file names, e.g. 09-11-2026_171545 */
    public static final String NAME_STAMP = "dd-MM-yyyy_HHmmss";
    /** Regex for the timestamp part of a backup name: current dd-MM-yyyy or earlier yyyy-MM-dd form */
    public static final String STAMP_REGEX = "(?:\\d{2}-\\d{2}-\\d{4}|\\d{4}-\\d{2}-\\d{2})_\\d{6}";
    // Current name timestamp: dd-MM-yyyy_HHmmss
    private static final Pattern DMY = Pattern.compile( "(?<!\\d)(\\d{2})-(\\d{2})-(\\d{4})_(\\d{2})(\\d{2})(\\d{2})(?!\\d)" );
    // Earlier name timestamp: yyyy-MM-dd_HHmmss
    private static final Pattern YMD = Pattern.compile( "(?<!\\d)(\\d{4})-(\\d{2})-(\\d{2})_(\\d{2})(\\d{2})(\\d{2})(?!\\d)" );

    /**
     * @description Prevents instantiation
     */
    private Retention() {
    }

    /**
     * @description Deletes ".partial" files (interrupted backups) and checksum files whose backup is gone
     * @param dir Backup folder
     * @return void
     */
    public static void cleanupLeftovers( File dir ) {
        File[] files = dir.listFiles(); // Folder contents
        if ( files == null ) {
            return;
        }
        for ( File f : files ) {
            String n = f.getName(); // File name
            boolean partial = n.endsWith( FileUtil.PARTIAL_SUFFIX ) || n.endsWith( ".sha256.tmp" ); // Interrupted write
            boolean orphanSha = n.endsWith( FileUtil.SHA_SUFFIX )
                    && !new File( dir, n.substring( 0, n.length() - FileUtil.SHA_SUFFIX.length() ) ).exists(); // Checksum without backup
            if ( f.isFile() && ( partial || orphanSha ) ) {
                Log.info( "Removing leftover from an interrupted run: " + f );
                if ( !f.delete() ) {
                    Log.warn( "Could not delete " + f );
                }
            }
        }
    }

    /**
     * @description Formats a backup timestamp for file names: dd-MM-yyyy_HHmmss (e.g. 09-11-2026_171545)
     * @param millis Time in milliseconds
     * @return String timestamp for the file name
     */
    public static String nameStamp( long millis ) {
        return new SimpleDateFormat( NAME_STAMP ).format( new Date( millis ) );
    }

    /**
     * @description Reads the date and time embedded in a backup file name. Understands the current
     *              dd-MM-yyyy_HHmmss names and the earlier yyyy-MM-dd_HHmmss ones, so older backups are
     *              still counted and rotated out.
     * @param name Backup file name
     * @return LocalDateTime embedded timestamp, or null when the name has none
     */
    public static LocalDateTime stampOf( String name ) {
        try {
            Matcher m = DMY.matcher( name ); // Current names
            if ( m.find() ) {
                return LocalDateTime.of( Integer.parseInt( m.group( 3 ) ), Integer.parseInt( m.group( 2 ) ), Integer.parseInt( m.group( 1 ) ),
                        Integer.parseInt( m.group( 4 ) ), Integer.parseInt( m.group( 5 ) ), Integer.parseInt( m.group( 6 ) ) );
            }
            m = YMD.matcher( name ); // Earlier names
            if ( m.find() ) {
                return LocalDateTime.of( Integer.parseInt( m.group( 1 ) ), Integer.parseInt( m.group( 2 ) ), Integer.parseInt( m.group( 3 ) ),
                        Integer.parseInt( m.group( 4 ) ), Integer.parseInt( m.group( 5 ) ), Integer.parseInt( m.group( 6 ) ) );
            }
        } catch ( Exception e ) {
            // Impossible date such as 31-02-2026: treated as having no timestamp
        }
        return null;
    }

    /**
     * @description Lists verified backups (name matches pattern and a .sha256 file exists), newest first.
     *              Sorted by the timestamp inside the name (dd-MM-yyyy names do not sort by date as text).
     * @param dir     Backup folder
     * @param pattern Regex the backup file name must match fully
     * @return List of File, newest first
     */
    public static List<File> verifiedBackups( File dir, Pattern pattern ) {
        List<File> out = new ArrayList<File>(); // Matching backups
        File[] files = dir.listFiles(); // Folder contents
        if ( files == null ) {
            return out;
        }
        for ( File f : files ) {
            if ( f.isFile() && pattern.matcher( f.getName() ).matches()
                    && new File( dir, f.getName() + FileUtil.SHA_SUFFIX ).isFile() ) {
                out.add( f );
            }
        }
        Collections.sort( out, ( a, b ) -> {
            LocalDateTime ta = stampOf( a.getName() ); // Timestamp of a
            LocalDateTime tb = stampOf( b.getName() ); // Timestamp of b
            if ( ta != null && tb != null && !ta.equals( tb ) ) {
                return tb.compareTo( ta );
            }
            return b.getName().compareTo( a.getName() );
        } );
        return out;
    }

    /**
     * @description Deletes verified backups beyond the newest "keep" ones, then (optionally) the old v1 backups
     *              once there are already "keep" verified v2 backups to replace them.
     * @param dir          Backup folder
     * @param pattern      Regex of v2 backup names
     * @param keep         How many verified backups to keep (at least 1)
     * @param legacy       Regex of v1 backup names (DD-MM-YYYY folders / .sql files), or null
     * @param deleteLegacy Whether v1 backups may be deleted
     * @return void
     */
    public static void apply( File dir, Pattern pattern, int keep, Pattern legacy, boolean deleteLegacy ) {
        keep = Math.max( 1, keep );
        List<File> backups = verifiedBackups( dir, pattern ); // Newest first
        for ( int i = keep; i < backups.size(); i++ ) {
            File old = backups.get( i ); // Backup past the retention limit
            Log.info( "Retention: deleting old backup " + old.getName() );
            if ( !old.delete() ) {
                Log.warn( "Could not delete " + old );
                continue;
            }
            new File( dir, old.getName() + FileUtil.SHA_SUFFIX ).delete();
        }

        if ( legacy == null || !deleteLegacy || backups.size() < keep ) {
            return;
        }
        File[] files = dir.listFiles(); // Folder contents
        if ( files == null ) {
            return;
        }
        for ( File f : files ) {
            if ( !legacy.matcher( f.getName() ).matches() ) {
                continue;
            }
            Log.info( "Retention: deleting v1 backup " + f.getName() + " (replaced by " + keep + " verified backups)" );
            try {
                FileUtil.deleteRecursive( f );
            } catch ( Exception e ) {
                Log.warn( "Could not delete v1 backup " + f + ": " + e.getMessage() );
            }
        }
    }
}
