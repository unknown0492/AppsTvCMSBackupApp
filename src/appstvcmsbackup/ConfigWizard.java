package appstvcmsbackup;

import java.io.BufferedReader;
import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * @description Interactive setup of the config file (--configure) and a readable summary of it (--show-config).
 *              Every answer is checked on the spot: folders must exist, the MySQL login is actually tested,
 *              the backup drive must be a separate drive. A required value cannot be skipped. The finished
 *              config is written durably (previous version kept as config.bak) and the drive is marked.
 *
 *              Steps (all by default, or only those named after --configure):
 *                hotel, cms, database, drive, schedule, cloud
 * @author Sohail
 */
public final class ConfigWizard {

    /** All wizard steps in order */
    public static final List<String> ALL_STEPS = Arrays.asList( "hotel", "cms", "database", "drive", "schedule", "cloud" );

    // Key order of the written config; "" inserts a blank line between groups
    private static final String[] LAYOUT = {
        "hotel_id", "hotel_name", "",
        "backup_drive", "cms_backup_folder", "db_backup_folder", "",
        "cms_source_dir", "cms_backup_frequency_days", "cms_backups_count", "db_backups_count",
        "delete_legacy_backups", "min_free_space_mb", "schedule_time", "",
        "mysql_bin_dir", "db_host", "db_port", "db_name", "db_user", "db_password", "db_charset", "",
        "url_webservice", "access_key", "support_email"
    };
    // Largest file a FAT32 drive can hold
    private static final long FAT32_MAX_FILE = 4L * 1024 * 1024 * 1024 - 1;

    // Config file being edited
    private final File configFile;
    // Current values (existing config merged over defaults), in insertion order
    private final Map<String, Object> v = new LinkedHashMap<String, Object>();
    // Keyboard input
    private final BufferedReader in = new BufferedReader( new InputStreamReader( System.in ) );
    // Size of the CMS folder in bytes (-1 = not measured yet)
    private long cmsBytes = -1;
    // True when the chosen drive carries another hotel's marker that the user agreed to replace
    private boolean replaceMarker = false;

    /**
     * @description Creates the wizard
     * @param configFile Config file to create or update
     */
    private ConfigWizard( File configFile ) {
        this.configFile = configFile;
    }

    // =====================================================================================  --configure

    /**
     * @description Runs the interactive setup
     * @param appDir Application folder (holds config and logs)
     * @param steps  Steps to run; empty = all
     * @return int exit code: 0 saved, 2 failed / aborted
     */
    public static int configure( File appDir, List<String> steps ) {
        Log.init( new File( appDir, "logs" ) );
        List<String> todo = steps.isEmpty() ? ALL_STEPS : steps; // Steps to run
        for ( String s : todo ) {
            if ( !ALL_STEPS.contains( s ) ) {
                System.out.println( "Unknown step '" + s + "'. Valid steps: " + ALL_STEPS );
                return 2;
            }
        }
        ConfigWizard w = new ConfigWizard( new File( appDir, "config" ) ); // Wizard instance
        try {
            return w.runSteps( todo );
        } catch ( AbortException e ) {
            System.out.println();
            System.out.println( "Setup cancelled - nothing was saved." );
            return 2;
        } catch ( Exception e ) {
            System.out.println();
            System.out.println( "Setup failed: " + e );
            return 2;
        }
    }

    /**
     * @description Loads the current values, runs the requested steps, saves and marks the drive
     * @param todo Steps to run
     * @return int exit code
     * @throws Exception on unexpected errors
     */
    private int runSteps( List<String> todo ) throws Exception {
        loadValues();
        title( "AppsTV CMS Backup - configuration" );
        System.out.println( "Press Enter to keep the value shown in [brackets]. Required values cannot be left empty." );

        int n = 0; // Step counter for headings
        for ( String step : todo ) {
            n++;
            String label = "[" + n + "/" + todo.size() + "] "; // Heading prefix
            if ( "hotel".equals( step ) ) {
                heading( label + "Hotel" );
                stepHotel();
            } else if ( "cms".equals( step ) ) {
                heading( label + "CMS folder to back up" );
                stepCms();
            } else if ( "database".equals( step ) ) {
                heading( label + "MySQL database" );
                stepDatabase();
            } else if ( "drive".equals( step ) ) {
                heading( label + "Backup thumb drive" );
                stepDrive();
            } else if ( "schedule".equals( step ) ) {
                heading( label + "Daily schedule" );
                stepSchedule();
            } else if ( "cloud".equals( step ) ) {
                heading( label + "Cloud reporting" );
                stepCloud( true );
            }
        }
        // Cloud settings normally come preset from the shipped config; ask only if they are missing
        if ( !todo.contains( "cloud" ) && ( isEmpty( "url_webservice" ) || isEmpty( "access_key" ) ) ) {
            heading( "Cloud reporting (missing from config)" );
            stepCloud( false );
        }

        save();
        Config cfg; // Validated result
        try {
            cfg = Config.load( configFile );
        } catch ( BackupException e ) {
            System.out.println( e.getMessage() );
            return 2;
        }
        if ( todo.contains( "drive" ) ) {
            markDrive( cfg );
        }
        heading( "Saved" );
        printSummary( cfg );
        return 0;
    }

    /**
     * @description Fills v with defaults, then overlays the existing config (if readable)
     * @return void
     */
    private void loadValues() {
        String[][] defaults = {
            { "cms_backup_folder", "appstv_backups" }, { "db_backup_folder", "database_backups" },
            { "delete_legacy_backups", "yes" }, { "schedule_time", "02:00" },
            { "mysql_bin_dir", "C:/xampp/mysql/bin" }, { "db_host", "localhost" }, { "db_name", "appstv2" },
            { "db_user", "root" }, { "db_password", "" }, { "db_charset", "utf8mb4" },
            { "url_webservice", "https://excel-erp.appstvlive.com/webservice.php" }, { "support_email", "" }
        }; // String defaults
        for ( String[] d : defaults ) {
            v.put( d[ 0 ], d[ 1 ] );
        }
        v.put( "cms_backup_frequency_days", 5L );
        v.put( "cms_backups_count", 3L );
        v.put( "db_backups_count", 5L );
        v.put( "min_free_space_mb", 200L );
        v.put( "db_port", 3306L );
        if ( configFile.isFile() ) {
            try {
                JSONObject json = Config.readRaw( configFile ); // Existing values
                for ( Object k : json.keySet() ) {
                    v.put( k.toString(), json.get( k ) );
                }
            } catch ( BackupException e ) {
                System.out.println( "Note: the existing config could not be read (" + e.getMessage() + "). Starting from defaults." );
            }
        }
    }

    // ----------------------------------------------------------------------------------------- steps

    /**
     * @description Asks for hotel_id and hotel_name
     * @return void
     * @throws IOException on input error
     */
    private void stepHotel() throws IOException {
        System.out.println( "The hotel ID is the hotel's id in excel-erp (AppsTV CMS > Backup sites)." );
        put( "hotel_id", ask( "Hotel ID", str( "hotel_id" ), null,
                s -> s.matches( "\\d{1,9}" ) ? null : "Enter digits only, e.g. 27" ) );
        put( "hotel_name", ask( "Hotel name", str( "hotel_name" ), null,
                s -> s.length() > 150 ? "Too long (max 150 characters)" : null ) );
    }

    /**
     * @description Asks for the CMS folder; it must exist and contain files
     * @return void
     * @throws IOException on input error
     */
    private void stepCms() throws IOException {
        String def = str( "cms_source_dir" ); // Suggested folder
        if ( def.isEmpty() ) {
            for ( String c : new String[]{ "C:/xampp/htdocs/appstv2", "C:/xampp/htdocs/7on7" } ) {
                if ( new File( c ).isDirectory() ) {
                    def = c;
                    break;
                }
            }
        }
        String dir = ask( "CMS folder (e.g. C:/xampp/htdocs/appstv2)", def, ConfigWizard::normalisePath, s -> {
            File f = new File( s ); // Candidate folder
            if ( !f.isDirectory() ) {
                return "Folder not found: " + s;
            }
            String[] list = f.list(); // Contents
            if ( list == null || list.length == 0 ) {
                return "That folder is empty - choose the folder that holds the CMS files";
            }
            if ( f.getParentFile() == null ) {
                return "Choose the CMS folder itself, not a whole drive";
            }
            return null;
        } );
        put( "cms_source_dir", dir );
        System.out.print( "Measuring the folder... " );
        long[] size = folderSize( new File( dir ) ); // {files, bytes}
        cmsBytes = size[ 1 ];
        System.out.println( size[ 0 ] + " files, " + FileUtil.human( size[ 1 ] ) );
    }

    /**
     * @description Asks for the MySQL folder and database, then finds a working login (tries the usual XAMPP
     *              logins first, then asks for a password) and checks the database has tables
     * @return void
     * @throws IOException on input error
     */
    private void stepDatabase() throws IOException {
        put( "mysql_bin_dir", ask( "MySQL bin folder", str( "mysql_bin_dir" ), ConfigWizard::normalisePath, s -> {
            if ( !new File( s, "mysqldump.exe" ).isFile() || !new File( s, "mysql.exe" ).isFile() ) {
                return "mysqldump.exe / mysql.exe not found in " + s + " (normally C:/xampp/mysql/bin)";
            }
            return null;
        } ) );

        while ( true ) {
            put( "db_name", ask( "Database name", str( "db_name" ), null,
                    s -> s.matches( "[A-Za-z0-9_$\\-]+" ) ? null : "Invalid database name" ) );

            // Logins to try without asking: the current one, then XAMPP's defaults
            List<String[]> tries = new ArrayList<String[]>(); // {host, user, password}
            addTry( tries, str( "db_host" ), str( "db_user" ), str( "db_password" ) );
            addTry( tries, "localhost", "root", "" );
            addTry( tries, "127.0.0.1", "root", "" );

            String[] ok = null; // Working login + table count
            boolean down = false; // MySQL not reachable at all
            String lastError = ""; // Last error from MySQL
            System.out.print( "Checking the MySQL login... " );
            for ( String[] t : tries ) {
                String[] r = probe( t[ 0 ], t[ 1 ], t[ 2 ] ); // {"ok"|"error", detail}
                if ( "ok".equals( r[ 0 ] ) ) {
                    ok = new String[]{ t[ 0 ], t[ 1 ], t[ 2 ], r[ 1 ] };
                    break;
                }
                lastError = r[ 1 ];
                down = down || isConnectError( r[ 1 ] );
            }

            if ( ok == null && down ) {
                System.out.println( "FAILED" );
                System.out.println( "  MySQL is not running (" + firstLine( lastError ) + ")." );
                System.out.println( "  Start MySQL in the XAMPP Control Panel, then press Enter to try again." );
                readLine();
                continue;
            }
            if ( ok == null ) {
                System.out.println( "a password is needed" );
                ok = askLogin();
            } else {
                System.out.println( "OK" );
            }

            long tables = parseLong( ok[ 3 ] ); // Base tables in db_name
            if ( tables == 0 ) {
                System.out.println( "  Database '" + str( "db_name" ) + "' does not exist or has no tables. Check the name in phpMyAdmin." );
                continue;
            }
            put( "db_host", ok[ 0 ] );
            put( "db_user", ok[ 1 ] );
            put( "db_password", ok[ 2 ] );
            System.out.println( "  Login works: " + ok[ 1 ] + "@" + ok[ 0 ] + ( ok[ 2 ].isEmpty() ? " (no password)" : " (with password)" )
                    + " - database '" + str( "db_name" ) + "' has " + tables + " tables." );
            return;
        }
    }

    /**
     * @description Asks for MySQL user + password until a login works (tests localhost and 127.0.0.1)
     * @return String[] {host, user, password, tableCount}
     * @throws IOException on input error
     */
    private String[] askLogin() throws IOException {
        System.out.println( "  Tip: phpMyAdmin keeps the password in C:/xampp/phpMyAdmin/config.inc.php ($cfg['Servers'][$i]['password'])." );
        while ( true ) {
            String user = ask( "MySQL user", str( "db_user" ).isEmpty() ? "root" : str( "db_user" ), null, null ); // User name
            String pwd = askPassword( "MySQL password for " + user + " (typing is hidden)" ); // Password (may be empty)
            String lastError = ""; // Error from the last attempt
            for ( String host : new String[]{ str( "db_host" ).isEmpty() ? "localhost" : str( "db_host" ), "localhost", "127.0.0.1" } ) {
                String[] r = probe( host, user, pwd ); // {"ok"|"error", detail}
                if ( "ok".equals( r[ 0 ] ) ) {
                    return new String[]{ host, user, pwd, r[ 1 ] };
                }
                lastError = r[ 1 ];
            }
            System.out.println( "  Login failed: " + firstLine( lastError ) + " - try again." );
        }
    }

    /**
     * @description Lists the drives and asks which one is the backup drive
     * @return void
     * @throws IOException on input error
     */
    private void stepDrive() throws IOException {
        String sysDrive = System.getenv( "SystemDrive" ) == null ? "C:" : System.getenv( "SystemDrive" ).toUpperCase(); // Windows drive
        String srcDrive = driveOf( str( "cms_source_dir" ) ); // Drive holding the CMS
        System.out.println( "Plug in the USB thumb drive now if it is not plugged in. Drives found:" );
        File[] roots = File.listRoots(); // All drive letters
        for ( File r : roots == null ? new File[ 0 ] : roots ) {
            String letter = r.getPath().substring( 0, 2 ).toUpperCase(); // e.g. "D:"
            if ( letter.startsWith( "A" ) || letter.startsWith( "B" ) || !r.isDirectory() || r.getTotalSpace() == 0 ) {
                continue;
            }
            String note = ""; // Remark shown after the drive
            if ( letter.equals( sysDrive ) || letter.equals( srcDrive ) ) {
                note = "  (Windows / CMS drive - not allowed)";
            } else {
                String mk = markerHotel( r ); // hotel_id on the drive's marker, or null
                if ( mk != null ) {
                    note = mk.equals( str( "hotel_id" ) ) ? "  <- backup drive of this hotel" : "  (marked for hotel_id " + mk + ")";
                }
            }
            System.out.println( String.format( "   %s  %-6s %9s total, %9s free%s", letter, fsType( r ),
                    FileUtil.human( r.getTotalSpace() ), FileUtil.human( r.getUsableSpace() ), note ) );
        }

        while ( true ) {
            String cur = str( "backup_drive" ); // Current setting
            String def = cur.length() >= 2 && new File( cur.substring( 0, 2 ) + "\\" ).isDirectory() ? cur.substring( 0, 2 ).toUpperCase() : ""; // Suggestion
            String drive = ask( "Backup drive letter (e.g. D)", def, s -> {
                String t = s.trim().toUpperCase(); // Typed letter
                return t.matches( "[A-Z](:\\\\?|:/?)?" ) ? t.substring( 0, 1 ) + ":" : t;
            }, s -> {
                if ( !s.matches( "[A-Z]:" ) ) {
                    return "Type just the drive letter, e.g. D";
                }
                if ( s.startsWith( "A" ) || s.startsWith( "B" ) ) {
                    return "A: and B: cannot be used";
                }
                if ( s.equals( sysDrive ) || s.equals( srcDrive ) ) {
                    return s + " is the Windows / CMS drive. Backups must go to a separate USB drive.";
                }
                if ( !new File( s + "\\" ).isDirectory() ) {
                    return "Drive " + s + " not found - is the thumb drive plugged in?";
                }
                return null;
            } );
            File root = new File( drive + "\\" ); // Chosen drive

            if ( fsType( root ).toUpperCase().startsWith( "FAT" ) ) {
                long size = cmsSize(); // CMS bytes (zip is at most about this big)
                if ( size > FAT32_MAX_FILE * 0.9 ) {
                    System.out.println( "  " + drive + " is FAT32, which cannot hold files over 4 GB, and the CMS folder is "
                            + FileUtil.human( size ) + ". Reformat the drive as NTFS (this erases it) or choose another drive." );
                    continue;
                }
                if ( !yes( "  " + drive + " is FAT32 (4 GB file limit). NTFS is recommended. Use it anyway?" ) ) {
                    continue;
                }
            }
            long free = root.getUsableSpace(); // Free bytes
            if ( cmsSize() > 0 && free < cmsSize() ) {
                if ( !yes( "  Only " + FileUtil.human( free ) + " free but the CMS folder is " + FileUtil.human( cmsSize() )
                        + ". Backups will fail until space is freed. Use it anyway?" ) ) {
                    continue;
                }
            }
            String mk = markerHotel( root ); // Existing marker owner
            replaceMarker = false;
            if ( mk != null && !mk.equals( str( "hotel_id" ) ) ) {
                if ( !yes( "  " + drive + " is marked as the backup drive of hotel_id " + mk + ". Re-assign it to this hotel?" ) ) {
                    continue;
                }
                replaceMarker = true;
            }
            put( "backup_drive", drive );
            return;
        }
    }

    /**
     * @description Asks for the daily run time
     * @return void
     * @throws IOException on input error
     */
    private void stepSchedule() throws IOException {
        System.out.println( "The backup also runs 10 minutes after every start-up, so PCs switched off at night are still covered." );
        put( "schedule_time", ask( "Daily backup time, 24-hour HH:mm", str( "schedule_time" ),
                s -> s.trim().matches( "\\d:\\d\\d" ) ? "0" + s.trim() : s.trim(),
                s -> s.matches( "([01]\\d|2[0-3]):[0-5]\\d" ) ? null : "Use HH:mm, e.g. 02:00 or 23:30" ) );
    }

    /**
     * @description Asks for the webservice URL, access key and support email
     * @param askAll True to ask everything; false to ask only what is missing
     * @return void
     * @throws IOException on input error
     */
    private void stepCloud( boolean askAll ) throws IOException {
        if ( askAll || isEmpty( "url_webservice" ) ) {
            put( "url_webservice", ask( "Webservice URL", str( "url_webservice" ), null,
                    s -> s.matches( "https?://\\S+" ) ? null : "Must start with http:// or https://" ) );
        }
        if ( askAll || isEmpty( "access_key" ) ) {
            put( "access_key", ask( "Access key", str( "access_key" ), null,
                    s -> s.matches( "\\S+" ) ? null : "No spaces allowed" ) );
        }
        if ( askAll ) {
            System.out.println( "Optional: an extra address that receives this hotel's failure emails (type - to clear)." );
            String e = askOptional( "Support email", str( "support_email" ) ); // Optional email
            if ( !e.isEmpty() && !e.matches( "[^@\\s]+@[^@\\s]+\\.[^@\\s]+" ) ) {
                System.out.println( "  Not a valid email address - left unchanged." );
            } else {
                put( "support_email", e );
            }
        }
    }

    // ----------------------------------------------------------------------------------------- save

    /**
     * @description Writes the config file (keeps the previous one as config.bak)
     * @return void
     * @throws IOException when writing fails
     */
    private void save() throws IOException {
        if ( configFile.isFile() ) {
            Files.copy( configFile.toPath(), new File( configFile.getParentFile(), "config.bak" ).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING );
        }
        List<String> lines = new ArrayList<String>(); // Output lines; null = blank separator
        List<String> done = new ArrayList<String>(); // Keys written
        for ( String k : LAYOUT ) {
            if ( k.isEmpty() ) {
                lines.add( null );
            } else if ( v.containsKey( k ) ) {
                lines.add( "    \"" + k + "\": " + jsonValue( v.get( k ) ) );
                done.add( k );
            }
        }
        for ( Map.Entry<String, Object> e : v.entrySet() ) {
            if ( !done.contains( e.getKey() ) ) {
                lines.add( "    \"" + jsonEscape( e.getKey() ) + "\": " + jsonValue( e.getValue() ) );
            }
        }
        int last = lines.size() - 1; // Index of the last real line (no trailing comma)
        while ( last >= 0 && lines.get( last ) == null ) {
            last--;
        }
        StringBuilder sb = new StringBuilder( "{\r\n" ); // File text
        for ( int i = 0; i <= last; i++ ) {
            String l = lines.get( i ); // Current line
            if ( l == null ) {
                if ( i > 0 && lines.get( i - 1 ) != null ) {
                    sb.append( "\r\n" );
                }
                continue;
            }
            sb.append( l ).append( i < last ? "," : "" ).append( "\r\n" );
        }
        sb.append( "}\r\n" );
        // Double check that what we write parses back before replacing the file
        try {
            new JSONParser().parse( sb.toString() );
        } catch ( Exception e ) {
            throw new IOException( "Internal error: generated config is not valid JSON (" + e + ")" );
        }
        FileUtil.writeTextDurable( configFile, sb.toString() );
    }

    /**
     * @description Marks the backup drive for this hotel (replacing another hotel's marker when agreed)
     * @param cfg Validated configuration
     * @return void
     * @throws Exception when marking fails
     */
    private void markDrive( Config cfg ) throws Exception {
        File marker = new File( str( "backup_drive" ) + "\\", DriveLocator.MARKER ); // Marker file
        if ( replaceMarker && marker.isFile() ) {
            File old = new File( marker.getParentFile(), DriveLocator.MARKER + ".old" ); // Kept for reference
            FileUtil.moveReplace( marker, old );
        }
        DriveLocator.initDrive( cfg, str( "backup_drive" ) );
    }

    // =====================================================================================  --show-config

    /**
     * @description Prints the configuration, whether it is valid, the backup drive status and the newest backups
     * @param appDir Application folder
     * @return int exit code: 0 valid, 2 invalid / missing
     */
    public static int show( File appDir ) {
        Log.init( new File( appDir, "logs" ) );
        File file = new File( appDir, "config" ); // Config file
        title( "AppsTV CMS Backup " + AppsTvCMSBackup.VERSION + " - current configuration" );
        Config cfg; // Validated configuration
        try {
            cfg = Config.load( file );
        } catch ( BackupException e ) {
            System.out.println( e.getMessage() );
            System.out.println();
            System.out.println( "The configuration is NOT valid - backups will fail. Choose 'Change configuration' to fix it." );
            return 2;
        }
        printSummary( cfg );

        heading( "Backup drive" );
        try {
            File root = DriveLocator.locate( cfg ); // Verified drive
            System.out.println( "  Found at " + root + " - " + fsType( root ) + ", " + FileUtil.human( root.getUsableSpace() ) + " free" );
            listBackups( "Database backups", new File( root, cfg.dbBackupFolder ), DatabaseBackup.namePattern( cfg ), cfg.dbBackupsCount );
            File cmsDir = new File( root, cfg.cmsBackupFolder ); // CMS backup folder
            listBackups( "CMS backups", cmsDir, CmsBackup.namePattern(), cfg.cmsBackupsCount );
            String notDue = CmsBackup.notDueReason( cfg, cmsDir ); // null = due now
            System.out.println( "  Next CMS backup: " + ( notDue == null ? "due now (next run)" : notDue.replace( "CMS backup not due: ", "not due yet - " ) ) );
        } catch ( BackupException e ) {
            System.out.println( "  PROBLEM: " + e.getMessage() );
        }
        return 0;
    }

    /**
     * @description Prints the important settings (password masked)
     * @param c Configuration
     * @return void
     */
    private static void printSummary( Config c ) {
        row( "Hotel", c.hotelName + "  (hotel_id " + c.hotelId + ")" );
        row( "CMS folder", c.cmsSourceDir );
        row( "Backup drive", c.backupRoot + "   folders: " + c.cmsBackupFolder + ", " + c.dbBackupFolder );
        row( "CMS backup", "every " + c.cmsFrequencyDays + " day(s), keep " + c.cmsBackupsCount );
        row( "Database", c.dbName + " on " + c.dbUser + "@" + c.dbHost + ":" + c.dbPort
                + ( c.dbPassword.isEmpty() ? " (no password)" : " (password set)" ) + ", keep " + c.dbBackupsCount );
        row( "MySQL folder", c.mysqlBinDir );
        row( "Daily time", c.scheduleTime + " (and 10 minutes after start-up)" );
        row( "Cloud", c.urlWebservice );
        row( "Support email", c.supportEmail.isEmpty() ? "(none)" : c.supportEmail );
    }

    /**
     * @description Prints how many verified backups a folder holds and the newest one
     * @param label   Heading
     * @param dir     Folder
     * @param pattern File name pattern
     * @param keep    Configured number to keep
     * @return void
     */
    private static void listBackups( String label, File dir, java.util.regex.Pattern pattern, int keep ) {
        List<File> list = Retention.verifiedBackups( dir, pattern ); // Newest first
        System.out.println( "  " + label + ": " + list.size() + " of " + keep + ( list.isEmpty() ? "" : ", newest "
                + list.get( 0 ).getName() + " (" + FileUtil.human( list.get( 0 ).length() ) + ")" ) );
    }

    // =====================================================================================  helpers

    /**
     * @description Prompts until a valid value is given. Enter keeps the current value; a required value
     *              without a current value cannot be skipped.
     * @param label     Prompt text
     * @param current   Current value ("" = none)
     * @param normalise Optional input clean-up (may be null)
     * @param validate  Optional check returning an error message or null (may be null)
     * @return String accepted value
     * @throws IOException on input error
     */
    private String ask( String label, String current, Function<String, String> normalise, Function<String, String> validate ) throws IOException {
        while ( true ) {
            System.out.print( "  " + label + ( current.isEmpty() ? "" : " [" + current + "]" ) + ": " );
            String s = stripQuotes( readLine() ); // Typed text
            if ( s.isEmpty() ) {
                if ( current.isEmpty() ) {
                    System.out.println( "    This value is required." );
                    continue;
                }
                s = current;
            }
            if ( normalise != null ) {
                s = normalise.apply( s );
            }
            String err = validate == null ? null : validate.apply( s ); // Validation result
            if ( err == null ) {
                return s;
            }
            System.out.println( "    " + err );
        }
    }

    /**
     * @description Prompts for an optional value ("-" clears it, Enter keeps it)
     * @param label   Prompt text
     * @param current Current value
     * @return String value
     * @throws IOException on input error
     */
    private String askOptional( String label, String current ) throws IOException {
        System.out.print( "  " + label + ( current.isEmpty() ? "" : " [" + current + "]" ) + ": " );
        String s = stripQuotes( readLine() ); // Typed text
        if ( s.equals( "-" ) ) {
            return "";
        }
        return s.isEmpty() ? current : s;
    }

    /**
     * @description Reads a password without echo when a console is available (Enter = empty password)
     * @param label Prompt text
     * @return String password
     * @throws IOException on input error
     */
    private String askPassword( String label ) throws IOException {
        Console c = System.console(); // Real console, if any
        if ( c != null ) {
            char[] p = c.readPassword( "  %s: ", label ); // Hidden input
            if ( p == null ) {
                throw new AbortException();
            }
            return new String( p );
        }
        System.out.print( "  " + label + ": " );
        return readLine();
    }

    /**
     * @description Asks a yes/no question
     * @param question Question text
     * @return boolean true for yes
     * @throws IOException on input error
     */
    private boolean yes( String question ) throws IOException {
        while ( true ) {
            System.out.print( question + " (y/n): " );
            String s = readLine().toLowerCase(); // Answer
            if ( s.startsWith( "y" ) ) {
                return true;
            }
            if ( s.startsWith( "n" ) ) {
                return false;
            }
        }
    }

    /**
     * @description Reads one line from the keyboard
     * @return String trimmed line
     * @throws IOException on input error
     * @throws AbortException when input is closed (window closed / Ctrl+Z)
     */
    private String readLine() throws IOException {
        String s = in.readLine(); // Raw line
        if ( s == null ) {
            throw new AbortException();
        }
        return s.trim();
    }

    /**
     * @description Tests a MySQL login against the current mysql_bin_dir / db_port / db_name
     * @param host Host
     * @param user User
     * @param pwd  Password
     * @return String[] {"ok", tableCount} or {"error", message}
     */
    private String[] probe( String host, String user, String pwd ) {
        Config c = new Config(); // Minimal config for the probe
        c.mysqlBinDir = str( "mysql_bin_dir" ).replace( '/', File.separatorChar );
        c.dbHost = host;
        c.dbPort = (int) parseLong( str( "db_port" ).isEmpty() ? "3306" : str( "db_port" ) );
        c.dbUser = user;
        c.dbPassword = pwd;
        c.dbName = str( "db_name" );
        return DatabaseBackup.probeLogin( c );
    }

    /**
     * @description Adds a login to try, skipping duplicates and empty users
     * @param tries List of {host, user, password}
     * @param host  Host
     * @param user  User
     * @param pwd   Password
     * @return void
     */
    private static void addTry( List<String[]> tries, String host, String user, String pwd ) {
        if ( host.isEmpty() || user.isEmpty() ) {
            return;
        }
        for ( String[] t : tries ) {
            if ( t[ 0 ].equalsIgnoreCase( host ) && t[ 1 ].equals( user ) && t[ 2 ].equals( pwd ) ) {
                return;
            }
        }
        tries.add( new String[]{ host, user, pwd } );
    }

    /**
     * @description True when a MySQL error means the server could not be reached (as opposed to a bad login)
     * @param err Error text
     * @return boolean
     */
    private static boolean isConnectError( String err ) {
        return err.contains( "ERROR 2003" ) || err.contains( "ERROR 2002" ) || err.contains( "ERROR 2013" )
                || err.toLowerCase().contains( "can't connect" );
    }

    /**
     * @description Size of the CMS folder (measured once)
     * @return long bytes, 0 when unknown
     */
    private long cmsSize() {
        if ( cmsBytes < 0 ) {
            String dir = str( "cms_source_dir" ); // Configured CMS folder
            cmsBytes = dir.isEmpty() || !new File( dir ).isDirectory() ? 0 : folderSize( new File( dir ) )[ 1 ];
        }
        return cmsBytes;
    }

    /**
     * @description Counts files and bytes below a folder (unreadable entries are skipped)
     * @param dir Folder
     * @return long[] {files, bytes}
     */
    private static long[] folderSize( File dir ) {
        final long[] total = { 0, 0 }; // {files, bytes}
        try {
            Files.walkFileTree( dir.toPath(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile( Path file, BasicFileAttributes attrs ) {
                    total[ 0 ]++;
                    total[ 1 ] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed( Path file, IOException exc ) {
                    return FileVisitResult.CONTINUE;
                }
            } );
        } catch ( IOException ignored ) {
            // Partial total is good enough for an estimate
        }
        return total;
    }

    /**
     * @description File system type of a drive (NTFS, FAT32, exFAT...)
     * @param root Drive root
     * @return String type, or "?" when unknown
     */
    private static String fsType( File root ) {
        try {
            return Files.getFileStore( root.toPath() ).type();
        } catch ( Exception e ) {
            return "?";
        }
    }

    /**
     * @description hotel_id stored in a drive's marker file
     * @param root Drive root
     * @return String hotel_id, "?" when unreadable, null when there is no marker
     */
    private static String markerHotel( File root ) {
        File m = new File( root, DriveLocator.MARKER ); // Marker file
        if ( !m.isFile() ) {
            return null;
        }
        try {
            Object id = ( (JSONObject) new JSONParser().parse( FileUtil.readText( m ) ) ).get( "hotel_id" ); // Stored id
            return id == null ? "?" : id.toString().trim();
        } catch ( Exception e ) {
            return "?";
        }
    }

    /**
     * @description Drive letter of a path, e.g. "C:"
     * @param path Path
     * @return String upper-case drive letter with colon, or "" when none
     */
    private static String driveOf( String path ) {
        return path.length() >= 2 && path.charAt( 1 ) == ':' ? path.substring( 0, 2 ).toUpperCase() : "";
    }

    /**
     * @description Cleans a typed folder path: forward slashes, no trailing slash
     * @param s Typed path
     * @return String normalised path
     */
    private static String normalisePath( String s ) {
        String p = s.trim().replace( '\\', '/' ); // Forward slashes
        while ( p.length() > 3 && p.endsWith( "/" ) ) {
            p = p.substring( 0, p.length() - 1 );
        }
        return p;
    }

    /**
     * @description Removes surrounding double quotes (paths pasted from Explorer often have them)
     * @param s Text
     * @return String unquoted text
     */
    private static String stripQuotes( String s ) {
        String t = s.trim(); // Trimmed text
        if ( t.length() >= 2 && t.startsWith( "\"" ) && t.endsWith( "\"" ) ) {
            t = t.substring( 1, t.length() - 1 ).trim();
        }
        return t;
    }

    /**
     * @description Current value of a key as a string ("" when missing)
     * @param key Config key
     * @return String value
     */
    private String str( String key ) {
        Object o = v.get( key ); // Raw value
        return o == null ? "" : o.toString().trim();
    }

    /**
     * @description True when a key has no value
     * @param key Config key
     * @return boolean
     */
    private boolean isEmpty( String key ) {
        return str( key ).isEmpty();
    }

    /**
     * @description Stores a value
     * @param key   Config key
     * @param value Value
     * @return void
     */
    private void put( String key, String value ) {
        v.put( key, value );
    }

    /**
     * @description Formats a value for JSON (numbers / booleans bare, everything else quoted)
     * @param o Value
     * @return String JSON literal
     */
    private static String jsonValue( Object o ) {
        if ( o instanceof Number || o instanceof Boolean ) {
            return o.toString();
        }
        return "\"" + jsonEscape( o == null ? "" : o.toString() ) + "\"";
    }

    /**
     * @description Escapes a string for JSON (keeps "/" readable, unlike json-simple's escape)
     * @param s Text
     * @return String escaped text
     */
    private static String jsonEscape( String s ) {
        StringBuilder sb = new StringBuilder( s.length() + 8 ); // Output
        for ( char ch : s.toCharArray() ) {
            switch ( ch ) {
                case '"':
                    sb.append( "\\\"" );
                    break;
                case '\\':
                    sb.append( "\\\\" );
                    break;
                default:
                    if ( ch < 32 ) {
                        sb.append( String.format( "\\u%04x", (int) ch ) );
                    } else {
                        sb.append( ch );
                    }
            }
        }
        return sb.toString();
    }

    /**
     * @description Parses a long, returning 0 on failure
     * @param s Text
     * @return long
     */
    private static long parseLong( String s ) {
        try {
            return Long.parseLong( s.trim() );
        } catch ( Exception e ) {
            return 0;
        }
    }

    /**
     * @description First line of a (possibly multi-line) message
     * @param s Message
     * @return String first line
     */
    private static String firstLine( String s ) {
        int i = s.indexOf( '\n' ); // End of first line
        return ( i < 0 ? s : s.substring( 0, i ) ).trim();
    }

    /**
     * @description Prints a title banner
     * @param s Title
     * @return void
     */
    private static void title( String s ) {
        System.out.println();
        System.out.println( "==================================================================" );
        System.out.println( "  " + s );
        System.out.println( "==================================================================" );
    }

    /**
     * @description Prints a section heading
     * @param s Heading
     * @return void
     */
    private static void heading( String s ) {
        System.out.println();
        System.out.println( "---- " + s + " ----" );
    }

    /**
     * @description Prints a "label: value" row
     * @param label Label
     * @param value Value
     * @return void
     */
    private static void row( String label, String value ) {
        System.out.println( String.format( "  %-14s %s", label + ":", value ) );
    }

    /**
     * @description Thrown when the keyboard input is closed, to cancel the wizard without saving
     */
    private static final class AbortException extends RuntimeException {
        // Serialization id
        private static final long serialVersionUID = 1L;
    }
}
