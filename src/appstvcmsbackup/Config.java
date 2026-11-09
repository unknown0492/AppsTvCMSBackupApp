package appstvcmsbackup;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

/**
 * @description Loads and validates the "config" JSON file that sits next to the jar.
 *              Every problem is collected and reported together, so a bad config fails loudly
 *              instead of silently running with empty values (the bug in v1).
 * @author Sohail
 */
public class Config {

    // Cloud id of this hotel (cms_backup_hotel_sites.id)
    public String hotelId;
    // Display name of this hotel
    public String hotelName;
    // Preferred backup drive root, e.g. "I:" (the drive is still identified by its marker file)
    public String backupRoot;
    // Folder on the backup drive holding CMS zips
    public String cmsBackupFolder;
    // Folder on the backup drive holding database dumps
    public String dbBackupFolder;
    // Folder to back up, e.g. C:/xampp/htdocs/7on7
    public String cmsSourceDir;
    // A CMS backup is taken when the newest verified one is this many days old (or older)
    public int cmsFrequencyDays;
    // How many verified CMS zips to keep
    public int cmsBackupsCount;
    // How many verified database dumps to keep
    public int dbBackupsCount;
    // Folder containing mysqldump.exe and mysql.exe
    public String mysqlBinDir;
    // MySQL host
    public String dbHost;
    // MySQL port
    public int dbPort;
    // Database to dump
    public String dbName;
    // MySQL user
    public String dbUser;
    // MySQL password (empty when none)
    public String dbPassword;
    // Character set passed to mysqldump
    public String dbCharset;
    // Webservice URL that receives reports
    public String urlWebservice;
    // Access key for the webservice (app_access_keys.key)
    public String accessKey;
    // Support email included in reports
    public String supportEmail;
    // Minimum free space to leave on the drive after a backup (MB)
    public long minFreeSpaceMb;
    // Delete v1 backups (DD-MM-YYYY names) once enough verified v2 backups exist
    public boolean deleteLegacyBackups;
    // Daily time for the scheduled task, HH:mm
    public String scheduleTime;

    // Problems found while loading
    private final List<String> errors = new ArrayList<String>();

    /**
     * @description Loads the config file and validates every value
     * @param file The config file
     * @return Config validated configuration
     * @throws BackupException listing every problem found
     */
    public static Config load( File file ) throws BackupException {
        if ( !file.isFile() ) {
            throw new BackupException( "Config file not found: " + file.getAbsolutePath() );
        }
        String raw; // File contents
        try {
            raw = FileUtil.readText( file );
        } catch ( Exception e ) {
            throw new BackupException( "Cannot read config file " + file + ": " + e.getMessage(), e );
        }
        JSONObject json; // Parsed config
        try {
            json = (JSONObject) new JSONParser().parse( fixLoneBackslashes( raw ) );
        } catch ( ParseException e ) {
            throw new BackupException( "Config file is not valid JSON (" + e + "). Tip: write paths with forward slashes, e.g. \"C:/xampp/htdocs/7on7\"" );
        } catch ( ClassCastException e ) {
            throw new BackupException( "Config file must contain a JSON object { ... }" );
        }

        Config c = new Config(); // Result being built
        c.hotelId = c.str( json, "hotel_id", null );
        c.hotelName = c.str( json, "hotel_name", null );
        c.backupRoot = c.path( json, "backup_drive", null );
        c.cmsBackupFolder = c.str( json, "cms_backup_folder", "appstv_backups" );
        c.dbBackupFolder = c.str( json, "db_backup_folder", "database_backups" );
        c.cmsSourceDir = c.path( json, "cms_source_dir", null );
        c.cmsFrequencyDays = c.integer( json, "cms_backup_frequency_days", 5, 1, 60 );
        c.cmsBackupsCount = c.integer( json, "cms_backups_count", 3, 1, 50 );
        c.dbBackupsCount = c.integer( json, "db_backups_count", 5, 1, 365 );
        c.mysqlBinDir = c.path( json, "mysql_bin_dir", "C:/xampp/mysql/bin" );
        c.dbHost = c.str( json, "db_host", "localhost" );
        c.dbPort = c.integer( json, "db_port", 3306, 1, 65535 );
        c.dbName = c.str( json, "db_name", "appstv2" );
        c.dbUser = c.str( json, "db_user", "root" );
        c.dbPassword = c.str( json, "db_password", "" );
        c.dbCharset = c.str( json, "db_charset", "utf8mb4" );
        c.urlWebservice = c.str( json, "url_webservice", null );
        c.accessKey = c.str( json, "access_key", null );
        c.supportEmail = c.str( json, "support_email", "" );
        c.minFreeSpaceMb = c.integer( json, "min_free_space_mb", 200, 0, 1000000 );
        c.deleteLegacyBackups = "yes".equalsIgnoreCase( c.str( json, "delete_legacy_backups", "yes" ) );
        c.scheduleTime = c.str( json, "schedule_time", "02:00" );

        if ( c.hotelId != null && !c.hotelId.matches( "\\d+" ) ) {
            c.errors.add( "hotel_id must be a number (the hotel's id in the cloud)" );
        }
        if ( c.dbName != null && !c.dbName.matches( "[A-Za-z0-9_$\\-]+" ) ) {
            c.errors.add( "db_name contains invalid characters" );
        }
        if ( c.dbCharset != null && !c.dbCharset.matches( "[A-Za-z0-9_]+" ) ) {
            c.errors.add( "db_charset contains invalid characters" );
        }
        if ( c.cmsBackupFolder != null && !c.cmsBackupFolder.matches( "[A-Za-z0-9_\\- ]+" ) ) {
            c.errors.add( "cms_backup_folder must be a plain folder name (no slashes)" );
        }
        if ( c.dbBackupFolder != null && !c.dbBackupFolder.matches( "[A-Za-z0-9_\\- ]+" ) ) {
            c.errors.add( "db_backup_folder must be a plain folder name (no slashes)" );
        }
        if ( c.scheduleTime != null && !c.scheduleTime.matches( "([01]\\d|2[0-3]):[0-5]\\d" ) ) {
            c.errors.add( "schedule_time must be HH:mm, e.g. 02:00" );
        }
        if ( c.urlWebservice != null && !c.urlWebservice.matches( "https?://.+" ) ) {
            c.errors.add( "url_webservice must start with http:// or https://" );
        }

        if ( !c.errors.isEmpty() ) {
            StringBuilder sb = new StringBuilder( "Config file " + file.getName() + " has errors:" ); // Combined message
            for ( String e : c.errors ) {
                sb.append( "\n  - " ).append( e );
            }
            throw new BackupException( sb.toString() );
        }
        return c;
    }

    /**
     * @description Doubles any backslash that is not a valid JSON escape, so "C:\xampp" still parses.
     *              Paths are additionally checked for control characters (e.g. "C:\temp" -> TAB) in path().
     * @param raw Raw config text
     * @return String fixed text
     */
    private static String fixLoneBackslashes( String raw ) {
        StringBuilder sb = new StringBuilder( raw.length() + 16 ); // Output
        for ( int i = 0; i < raw.length(); i++ ) {
            char ch = raw.charAt( i ); // Current character
            if ( ch == '\\' ) {
                char next = ( i + 1 < raw.length() ) ? raw.charAt( i + 1 ) : ' '; // Following character
                if ( "\"\\/bfnrtu".indexOf( next ) >= 0 ) {
                    sb.append( ch ).append( next );
                    i++;
                    continue;
                }
                sb.append( "\\\\" );
                continue;
            }
            sb.append( ch );
        }
        return sb.toString();
    }

    /**
     * @description Reads a string value (numbers are accepted and converted)
     * @param json JSON object
     * @param key  Key
     * @param def  Default, or null when the key is required
     * @return String trimmed value (or default)
     */
    private String str( JSONObject json, String key, String def ) {
        Object v = json.get( key ); // Raw value
        if ( v == null || v.toString().trim().isEmpty() ) {
            if ( def == null ) {
                errors.add( "\"" + key + "\" is missing" );
            }
            return def;
        }
        return v.toString().trim();
    }

    /**
     * @description Reads a path value and rejects control characters caused by "\t", "\n" etc. in JSON
     * @param json JSON object
     * @param key  Key
     * @param def  Default, or null when required
     * @return String path with forward slashes converted to the platform separator
     */
    private String path( JSONObject json, String key, String def ) {
        String v = str( json, key, def ); // Raw path
        if ( v == null ) {
            return null;
        }
        for ( char ch : v.toCharArray() ) {
            if ( ch < 32 ) {
                errors.add( "\"" + key + "\" contains an invalid character - write the path with forward slashes, e.g. C:/xampp/htdocs" );
                return v;
            }
        }
        return v.replace( '/', File.separatorChar );
    }

    /**
     * @description Reads an integer value (string or number) and checks its range
     * @param json JSON object
     * @param key  Key
     * @param def  Default
     * @param min  Minimum allowed
     * @param max  Maximum allowed
     * @return int value
     */
    private int integer( JSONObject json, String key, int def, int min, int max ) {
        String v = str( json, key, String.valueOf( def ) ); // Raw value
        try {
            int n = Integer.parseInt( v ); // Parsed value
            if ( n < min || n > max ) {
                errors.add( "\"" + key + "\" must be between " + min + " and " + max );
            }
            return n;
        } catch ( NumberFormatException e ) {
            errors.add( "\"" + key + "\" must be a whole number" );
            return def;
        }
    }
}
