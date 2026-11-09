package appstvcmsbackup;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * @description Finds the backup thumb drive by its marker file (appstv_backup.id) rather than by
 *              drive letter alone. This stops the app from backing up to the wrong disk when Windows
 *              assigns a different letter, or when a different device takes the configured letter.
 * @author Sohail
 */
public final class DriveLocator {

    /** Name of the marker file at the root of the backup drive */
    public static final String MARKER = "appstv_backup.id";

    /**
     * @description Prevents instantiation
     */
    private DriveLocator() {
    }

    /**
     * @description Locates the backup drive: tries the configured root first, then every other drive.
     *              The marker's hotel_id must match this hotel, the drive must differ from the CMS source
     *              drive, and it must pass a real write/read test.
     * @param cfg Configuration
     * @return File root folder of the verified backup drive
     * @throws BackupException when no valid drive is found
     */
    public static File locate( Config cfg ) throws BackupException {
        List<File> candidates = new ArrayList<File>(); // Places to look for the marker, in priority order
        File preferred = normaliseRoot( cfg.backupRoot ); // Configured location
        candidates.add( preferred );
        File[] roots = File.listRoots(); // All drive letters
        if ( roots != null ) {
            for ( File r : roots ) {
                String letter = r.getPath().toUpperCase(); // e.g. "E:\"
                // Skip floppy letters: probing them can pop a "no disk" dialog on old Windows
                if ( letter.startsWith( "A:" ) || letter.startsWith( "B:" ) ) {
                    continue;
                }
                if ( !r.getAbsolutePath().equalsIgnoreCase( preferred.getAbsolutePath() ) ) {
                    candidates.add( r );
                }
            }
        }

        List<String> notes = new ArrayList<String>(); // Why candidates were rejected (for the error message)
        for ( File root : candidates ) {
            File marker = new File( root, MARKER ); // Marker file on this candidate
            if ( !marker.isFile() ) {
                continue;
            }
            String markerHotel = readMarkerHotelId( marker ); // hotel_id written in the marker
            if ( !cfg.hotelId.equals( markerHotel ) ) {
                notes.add( root + " has a marker for hotel_id " + markerHotel + " (expected " + cfg.hotelId + ")" );
                Log.warn( "Ignoring " + root + ": marker belongs to hotel_id " + markerHotel );
                continue;
            }
            if ( sameDrive( root, new File( cfg.cmsSourceDir ) ) ) {
                throw new BackupException( "Backup drive " + root + " is the same drive as the CMS source " + cfg.cmsSourceDir
                        + ". Backups must go to a separate (removable) drive." );
            }
            writeTest( root );
            if ( root != preferred ) {
                Log.warn( "Backup drive found at " + root + " instead of the configured " + preferred + " (drive letter changed)" );
            }
            return root;
        }

        StringBuilder sb = new StringBuilder( "Backup drive not found. No drive contains a valid " + MARKER
                + " file for hotel_id " + cfg.hotelId + ". Is the thumb drive plugged in?" ); // Error text
        for ( String n : notes ) {
            sb.append( " | " ).append( n );
        }
        if ( new File( preferred, cfg.dbBackupFolder ).isDirectory() || new File( preferred, cfg.cmsBackupFolder ).isDirectory() ) {
            sb.append( " | " ).append( preferred ).append( " has backup folders but no marker - run once with: --init-drive " )
                    .append( preferred.getPath().replaceAll( "[\\\\/]+$", "" ) );
        }
        throw new BackupException( sb.toString() );
    }

    /**
     * @description Creates the marker file on a drive so it is recognised as this hotel's backup drive
     * @param cfg  Configuration
     * @param path Drive root or folder to mark (e.g. "I:")
     * @return void
     * @throws BackupException when the drive is invalid or already belongs to another hotel
     */
    public static void initDrive( Config cfg, String path ) throws BackupException {
        File root = normaliseRoot( path ); // Folder to mark
        if ( !root.isDirectory() ) {
            throw new BackupException( "Cannot mark " + root + ": it does not exist" );
        }
        if ( sameDrive( root, new File( cfg.cmsSourceDir ) ) ) {
            throw new BackupException( "Refusing to mark " + root + ": it is the same drive as the CMS source " + cfg.cmsSourceDir );
        }
        File marker = new File( root, MARKER ); // Marker file to create
        if ( marker.isFile() ) {
            String existing = readMarkerHotelId( marker ); // hotel_id already on the drive
            if ( !cfg.hotelId.equals( existing ) ) {
                throw new BackupException( root + " is already marked for hotel_id " + existing + ". Delete " + marker + " manually if this is intended." );
            }
            Log.info( root + " is already marked for this hotel" );
            return;
        }
        JSONObject json = new JSONObject(); // Marker content
        json.put( "hotel_id", cfg.hotelId );
        json.put( "hotel_name", cfg.hotelName );
        json.put( "created_at", new SimpleDateFormat( "yyyy-MM-dd HH:mm:ss" ).format( new Date() ) );
        try {
            FileUtil.writeTextDurable( marker, json.toJSONString() );
        } catch ( Exception e ) {
            throw new BackupException( "Could not write marker " + marker + ": " + e.getMessage(), e );
        }
        writeTest( root );
        Log.info( "Marked " + root + " as the backup drive for " + cfg.hotelName + " (hotel_id " + cfg.hotelId + ")" );
    }

    /**
     * @description Turns "I:" / "I:\" / "I:/" / a folder path into a File
     * @param path Raw path
     * @return File root
     */
    private static File normaliseRoot( String path ) {
        String p = path.trim(); // Trimmed path
        if ( p.matches( "[A-Za-z]:" ) ) {
            p = p + File.separator;
        }
        return new File( p );
    }

    /**
     * @description Reads hotel_id from a marker file
     * @param marker Marker file
     * @return String hotel_id, or "?" when unreadable
     */
    private static String readMarkerHotelId( File marker ) {
        try {
            JSONObject json = (JSONObject) new JSONParser().parse( FileUtil.readText( marker ) ); // Marker content
            Object id = json.get( "hotel_id" ); // Stored id
            return id == null ? "?" : id.toString().trim();
        } catch ( Exception e ) {
            Log.warn( "Unreadable marker file " + marker + ": " + e.getMessage() );
            return "?";
        }
    }

    /**
     * @description True when both paths are on the same drive letter
     * @param a First path
     * @param b Second path
     * @return boolean
     */
    private static boolean sameDrive( File a, File b ) {
        String ra = a.getAbsoluteFile().toPath().getRoot().toString(); // Root of a
        String rb = b.getAbsoluteFile().toPath().getRoot().toString(); // Root of b
        return ra.equalsIgnoreCase( rb );
    }

    /**
     * @description Proves the drive is writable: writes, syncs, reads back and deletes a small file
     * @param root Drive root
     * @return void
     * @throws BackupException when the drive is read-only, full or failing
     */
    private static void writeTest( File root ) throws BackupException {
        File probe = new File( root, ".appstv_write_test" ); // Temporary probe file
        String content = "write-test-" + System.nanoTime(); // Unique content to read back
        FileOutputStream fos = null; // Probe writer
        try {
            fos = new FileOutputStream( probe );
            fos.write( content.getBytes( FileUtil.UTF8 ) );
            fos.flush();
            fos.getFD().sync();
            fos.close();
            fos = null;
            if ( !content.equals( FileUtil.readText( probe ) ) ) {
                throw new BackupException( "Backup drive " + root + " returned different data than was written - the drive may be failing" );
            }
        } catch ( BackupException e ) {
            throw e;
        } catch ( Exception e ) {
            throw new BackupException( "Backup drive " + root + " is not writable (" + e.getMessage() + "). Is it full, read-only or failing?", e );
        } finally {
            FileUtil.closeQuietly( fos );
            probe.delete();
        }
    }
}
