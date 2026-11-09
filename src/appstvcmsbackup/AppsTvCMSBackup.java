package appstvcmsbackup;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.io.OutputStreamWriter;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @description AppsTV CMS Backup v2 - entry point.
 *
 *              Each run:
 *              1. takes a single-instance lock and loads/validates the config
 *              2. delivers any reports still queued from earlier runs
 *              3. finds the backup drive by its marker file and write-tests it
 *              4. dumps the database (once per day), verifies it, then applies retention
 *              5. zips the CMS folder when due, verifies it, then applies retention
 *              6. reports every outcome (success, warning or failure) to the cloud
 *
 *              Usage:
 *                java -jar AppsTvCMSBackup.jar                 normal run (used by the scheduled task)
 *                java -jar AppsTvCMSBackup.jar --force         back up database and CMS now, even if not due
 *                java -jar AppsTvCMSBackup.jar --force-cms     back up the CMS now, even if not due
 *                java -jar AppsTvCMSBackup.jar --init-drive I: mark a thumb drive as this hotel's backup drive
 *                java -jar AppsTvCMSBackup.jar --install-task  create/replace the Windows scheduled task
 *                java -jar AppsTvCMSBackup.jar --test-report   send a test report to the cloud
 *
 *              Exit codes: 0 = OK, 1 = completed with warnings, 2 = a backup failed, 3 = already running.
 * @author Sohail
 */
public class AppsTvCMSBackup {

    /** Application version, sent with every report */
    public static final String VERSION = "2.0.0";
    /** Name of the Windows scheduled task */
    public static final String TASK_NAME = "AppsTV CMS Backup";

    /**
     * @description Entry point
     * @param args Command line arguments (see class comment)
     * @return void
     */
    public static void main( String[] args ) {
        System.exit( run( Arrays.asList( args ) ) );
    }

    /**
     * @description Runs the application and returns the exit code
     * @param args Command line arguments
     * @return int exit code
     */
    private static int run( List<String> args ) {
        File appDir = resolveAppDir(); // Folder holding the jar, config, logs and outbox
        Log.init( new File( appDir, "logs" ) );
        Log.info( "===== AppsTV CMS Backup " + VERSION + " started (args: " + args + ", folder: " + appDir + ") =====" );

        RandomAccessFile lockFile = null; // Kept open for the whole run
        FileLock lock = null; // Single-instance lock
        try {
            lockFile = new RandomAccessFile( new File( appDir, "backup.lock" ), "rw" );
            lock = lockFile.getChannel().tryLock();
        } catch ( Exception e ) {
            Log.warn( "Could not create lock file: " + e.getMessage() );
        }
        if ( lockFile != null && lock == null ) {
            Log.warn( "Another backup is already running - exiting" );
            FileUtil.closeQuietly( lockFile );
            return 3;
        }

        try {
            return runLocked( appDir, args );
        } catch ( Throwable t ) {
            Log.error( "Fatal error", t );
            return 2;
        } finally {
            Log.info( "===== Finished =====" );
            FileUtil.closeQuietly( lockFile );
        }
    }

    /**
     * @description The actual work, executed while holding the single-instance lock
     * @param appDir Application folder
     * @param args   Command line arguments
     * @return int exit code
     */
    private static int runLocked( File appDir, List<String> args ) {
        Config cfg; // Validated configuration
        try {
            cfg = Config.load( new File( appDir, "config" ) );
        } catch ( BackupException e ) {
            Log.error( e.getMessage(), null );
            Log.error( "BACKUP NOT DONE: fix the config file and run again", null );
            return 2;
        }
        StatusReporter reporter = new StatusReporter( cfg, appDir ); // Cloud reporting with offline queue
        reporter.flush();

        // ---- One-off commands
        if ( args.contains( "--install-task" ) ) {
            return installTask( cfg, appDir ) ? 0 : 2;
        }
        if ( args.contains( "--init-drive" ) ) {
            int i = args.indexOf( "--init-drive" ); // Position of the flag
            if ( i + 1 >= args.size() ) {
                Log.error( "Usage: --init-drive <drive>, e.g. --init-drive I:", null );
                return 2;
            }
            try {
                DriveLocator.initDrive( cfg, args.get( i + 1 ) );
                return 0;
            } catch ( BackupException e ) {
                Log.error( e.getMessage(), null );
                return 2;
            }
        }
        if ( args.contains( "--test-report" ) ) {
            BackupResult r = new BackupResult( BackupResult.TYPE_RUN ); // Test report
            r.succeed( "Test report from " + cfg.hotelName );
            reporter.report( r );
            return 0;
        }

        boolean forceAll = args.contains( "--force" ); // Ignore "already done today" / "not due"
        boolean forceCms = forceAll || args.contains( "--force-cms" ); // Ignore "not due" for CMS

        // ---- Locate and test the backup drive
        File root; // Root of the verified backup drive
        try {
            root = DriveLocator.locate( cfg );
            Log.info( "Backup drive: " + root + " (free " + FileUtil.human( root.getUsableSpace() ) + ")" );
        } catch ( BackupException e ) {
            BackupResult r = new BackupResult( BackupResult.TYPE_RUN ).fail( e.getMessage() ); // Drive problem
            Log.error( "BACKUP NOT DONE: " + e.getMessage(), null );
            reporter.report( r );
            return 2;
        }

        List<BackupResult> results = new ArrayList<BackupResult>(); // Outcomes of this run

        // ---- Database
        File dbDir = new File( root, cfg.dbBackupFolder ); // Database backup folder
        results.add( step( reporter, dbDir, () -> {
            BackupResult r = DatabaseBackup.run( cfg, dbDir, forceAll ); // Dump outcome
            if ( r.producedBackup() ) {
                Retention.apply( dbDir, DatabaseBackup.namePattern( cfg ), cfg.dbBackupsCount,
                        DatabaseBackup.legacyPattern(), cfg.deleteLegacyBackups );
            }
            return r;
        }, BackupResult.TYPE_DB ) );

        // ---- CMS files
        File cmsDir = new File( root, cfg.cmsBackupFolder ); // CMS backup folder
        results.add( step( reporter, cmsDir, () -> {
            String notDue = forceCms ? null : CmsBackup.notDueReason( cfg, cmsDir ); // Why it can be skipped
            if ( notDue != null ) {
                return new BackupResult( BackupResult.TYPE_CMS ).skip( notDue );
            }
            BackupResult r = CmsBackup.run( cfg, cmsDir ); // Zip outcome
            if ( r.producedBackup() ) {
                Retention.apply( cmsDir, CmsBackup.namePattern(), cfg.cmsBackupsCount,
                        CmsBackup.legacyPattern(), cfg.deleteLegacyBackups );
            }
            return r;
        }, BackupResult.TYPE_CMS ) );

        // ---- Exit code
        int code = 0; // Worst outcome
        for ( BackupResult r : results ) {
            if ( BackupResult.STATUS_FAILED.equals( r.status ) ) {
                code = 2;
            } else if ( BackupResult.STATUS_WARNING.equals( r.status ) && code < 1 ) {
                code = 1;
            }
        }
        return code;
    }

    /**
     * @description A backup step returning a result
     */
    private interface Step {
        /**
         * @description Runs the step
         * @return BackupResult outcome
         * @throws Exception on unexpected failure
         */
        BackupResult run() throws Exception;
    }

    /**
     * @description Runs one backup step so that nothing in it can stop the other step: prepares the folder,
     *              cleans leftovers, runs, logs and reports the outcome (skips are logged but not reported)
     * @param reporter Cloud reporter
     * @param dir      Backup folder for this step
     * @param step     The work
     * @param type     Backup type (for unexpected-error results)
     * @return BackupResult outcome
     */
    private static BackupResult step( StatusReporter reporter, File dir, Step step, String type ) {
        BackupResult r; // Outcome
        try {
            if ( !dir.isDirectory() && !dir.mkdirs() ) {
                throw new BackupException( "Cannot create backup folder " + dir );
            }
            Retention.cleanupLeftovers( dir );
            r = step.run();
        } catch ( BackupException e ) {
            r = new BackupResult( type ).fail( e.getMessage() );
        } catch ( Throwable t ) {
            Log.error( "Unexpected error in " + type + " backup", t );
            r = new BackupResult( type ).fail( "Unexpected error: " + t );
        }
        if ( BackupResult.STATUS_FAILED.equals( r.status ) ) {
            Log.error( r.toString(), null );
        } else if ( BackupResult.STATUS_WARNING.equals( r.status ) ) {
            Log.warn( r.toString() );
        } else {
            Log.info( r.toString() );
        }
        if ( !BackupResult.STATUS_SKIPPED.equals( r.status ) ) {
            reporter.report( r );
        }
        return r;
    }

    /**
     * @description Finds the folder holding the jar (or the project folder when run from NetBeans)
     * @return File application folder
     */
    private static File resolveAppDir() {
        try {
            File loc = new File( AppsTvCMSBackup.class.getProtectionDomain().getCodeSource().getLocation().toURI() ); // Jar file or classes dir
            File dir = loc.isFile() ? loc.getParentFile() : loc; // Folder
            // Running from NetBeans: build/classes -> project folder; dist/ -> project folder only if config is not in dist
            if ( dir.getName().equals( "classes" ) && dir.getParentFile() != null && dir.getParentFile().getName().equals( "build" ) ) {
                dir = dir.getParentFile().getParentFile();
            }
            return dir.getAbsoluteFile();
        } catch ( Exception e ) {
            return new File( "." ).getAbsoluteFile();
        }
    }

    /**
     * @description Creates (or replaces) the Windows scheduled task: daily at schedule_time plus 10 minutes after
     *              boot, runs as SYSTEM whether or not anyone is logged in, and runs as soon as possible when a
     *              scheduled start was missed (PC was off). Works on Windows 7 and later.
     * @param cfg    Configuration
     * @param appDir Application folder
     * @return boolean true when the task was created
     */
    private static boolean installTask( Config cfg, File appDir ) {
        try {
            File jar = new File( AppsTvCMSBackup.class.getProtectionDomain().getCodeSource().getLocation().toURI() ); // This jar
            if ( !jar.isFile() ) {
                Log.error( "--install-task must be run from the built jar (dist/AppsTvCMSBackup.jar)", null );
                return false;
            }
            // Oracle's update-proof shim: always points at the current Java, so the task survives Java auto-updates
            // (the versioned folder such as jre1.8.0_321 is deleted when Java updates)
            File javaw = new File( System.getenv( "ProgramData" ) == null ? "C:\\ProgramData" : System.getenv( "ProgramData" ),
                    "Oracle\\Java\\javapath\\javaw.exe" ); // Windowless Java
            if ( !javaw.isFile() ) {
                javaw = new File( System.getProperty( "java.home" ), "bin" + File.separator + "javaw.exe" );
                Log.warn( "Java shim not found; task uses " + javaw + " - re-run --install-task after a Java update" );
            }
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-16\"?>\r\n"
                    + "<Task version=\"1.2\" xmlns=\"http://schemas.microsoft.com/windows/2004/02/mit/task\">\r\n"
                    + "  <RegistrationInfo><Description>Daily verified backup of the AppsTV CMS database and files to the USB backup drive.</Description></RegistrationInfo>\r\n"
                    + "  <Triggers>\r\n"
                    + "    <CalendarTrigger><StartBoundary>2020-01-01T" + cfg.scheduleTime + ":00</StartBoundary><Enabled>true</Enabled>"
                    + "<ScheduleByDay><DaysInterval>1</DaysInterval></ScheduleByDay></CalendarTrigger>\r\n"
                    + "    <BootTrigger><Enabled>true</Enabled><Delay>PT10M</Delay></BootTrigger>\r\n"
                    + "  </Triggers>\r\n"
                    + "  <Principals><Principal id=\"Author\"><UserId>S-1-5-18</UserId><RunLevel>HighestAvailable</RunLevel></Principal></Principals>\r\n"
                    + "  <Settings>\r\n"
                    + "    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>\r\n"
                    + "    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>\r\n"
                    + "    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>\r\n"
                    + "    <StartWhenAvailable>true</StartWhenAvailable>\r\n"
                    + "    <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>\r\n"
                    + "    <IdleSettings><StopOnIdleEnd>false</StopOnIdleEnd><RestartOnIdle>false</RestartOnIdle></IdleSettings>\r\n"
                    + "    <AllowStartOnDemand>true</AllowStartOnDemand>\r\n"
                    + "    <Enabled>true</Enabled>\r\n"
                    + "    <Hidden>false</Hidden>\r\n"
                    + "    <RunOnlyIfIdle>false</RunOnlyIfIdle>\r\n"
                    + "    <WakeToRun>false</WakeToRun>\r\n"
                    + "    <ExecutionTimeLimit>PT6H</ExecutionTimeLimit>\r\n"
                    + "    <Priority>7</Priority>\r\n"
                    + "  </Settings>\r\n"
                    + "  <Actions Context=\"Author\"><Exec>"
                    + "<Command>" + xmlEscape( javaw.getAbsolutePath() ) + "</Command>"
                    + "<Arguments>-jar \"" + xmlEscape( jar.getAbsolutePath() ) + "\"</Arguments>"
                    + "<WorkingDirectory>" + xmlEscape( appDir.getAbsolutePath() ) + "</WorkingDirectory>"
                    + "</Exec></Actions>\r\n"
                    + "</Task>\r\n"; // Task Scheduler 1.2 definition (Vista / Windows 7+)

            File xmlFile = new File( appDir, "scheduled_task.xml" ); // schtasks wants UTF-16 with BOM
            Writer w = new OutputStreamWriter( new FileOutputStream( xmlFile ), "UTF-16LE" );
            try {
                w.write( '﻿' );
                w.write( xml );
            } finally {
                w.close();
            }

            Process p = new ProcessBuilder( "schtasks", "/Create", "/TN", TASK_NAME, "/XML", xmlFile.getAbsolutePath(), "/F" )
                    .redirectErrorStream( true ).start(); // schtasks call
            String out = FileUtil.drain( p.getInputStream(), 8192 ).trim(); // Its output
            int code = p.waitFor(); // Its exit code
            if ( code != 0 ) {
                Log.error( "schtasks failed (" + code + "): " + out + " - run this command from an Administrator command prompt", null );
                return false;
            }
            Log.info( "Scheduled task '" + TASK_NAME + "' installed: daily at " + cfg.scheduleTime
                    + " and 10 minutes after boot, runs as SYSTEM. " + out );
            return true;
        } catch ( Exception e ) {
            Log.error( "Could not install scheduled task", e );
            return false;
        }
    }

    /**
     * @description Escapes text for XML
     * @param s Text
     * @return String escaped
     */
    private static String xmlEscape( String s ) {
        return s.replace( "&", "&amp;" ).replace( "<", "&lt;" ).replace( ">", "&gt;" ).replace( "\"", "&quot;" );
    }
}
