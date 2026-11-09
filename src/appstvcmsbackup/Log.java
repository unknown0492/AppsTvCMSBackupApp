package appstvcmsbackup;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

/**
 * @description Minimal file + console logger. One file per month (logs/backup_yyyy-MM.log),
 *              files older than 12 months are deleted. Every line is flushed immediately
 *              so the log survives a crash or power cut.
 * @author Sohail
 */
public final class Log {

    // Directory holding the monthly log files (null until init is called)
    private static File logDir = null;
    // How many months of logs to keep
    private static final int KEEP_MONTHS = 12;

    /**
     * @description Prevents instantiation
     */
    private Log() {
    }

    /**
     * @description Initialises the logger and deletes log files older than KEEP_MONTHS
     * @param dir Directory to write log files into (created if missing)
     * @return void
     */
    public static synchronized void init( File dir ) {
        logDir = dir;
        if ( !logDir.exists() ) {
            logDir.mkdirs();
        }
        Calendar cutoff = Calendar.getInstance(); // Anything older than this month - KEEP_MONTHS goes
        cutoff.add( Calendar.MONTH, -KEEP_MONTHS );
        String cutoffName = "backup_" + new SimpleDateFormat( "yyyy-MM" ).format( cutoff.getTime() ) + ".log";
        File[] files = logDir.listFiles(); // Existing log files
        if ( files == null ) {
            return;
        }
        for ( File f : files ) {
            String n = f.getName(); // e.g. backup_2026-11.log (sorts chronologically)
            if ( n.matches( "backup_\\d{4}-\\d{2}\\.log" ) && n.compareTo( cutoffName ) < 0 ) {
                f.delete();
            }
        }
    }

    /**
     * @description Logs an informational line
     * @param msg Message
     * @return void
     */
    public static void info( String msg ) {
        write( "INFO ", msg );
    }

    /**
     * @description Logs a warning line
     * @param msg Message
     * @return void
     */
    public static void warn( String msg ) {
        write( "WARN ", msg );
    }

    /**
     * @description Logs an error line, with stack trace when a throwable is given
     * @param msg Message
     * @param t   Optional cause (may be null)
     * @return void
     */
    public static void error( String msg, Throwable t ) {
        if ( t == null ) {
            write( "ERROR", msg );
            return;
        }
        StringWriter sw = new StringWriter(); // Collects the stack trace text
        t.printStackTrace( new PrintWriter( sw ) );
        write( "ERROR", msg + System.lineSeparator() + sw.toString() );
    }

    /**
     * @description Writes one line to the console and to this month's log file
     * @param level Level label
     * @param msg   Message
     * @return void
     */
    private static synchronized void write( String level, String msg ) {
        Date now = new Date(); // Timestamp for this line
        String line = new SimpleDateFormat( "yyyy-MM-dd HH:mm:ss" ).format( now ) + " " + level + " " + msg;
        System.out.println( line );
        if ( logDir == null ) {
            return;
        }
        File file = new File( logDir, "backup_" + new SimpleDateFormat( "yyyy-MM" ).format( now ) + ".log" );
        Writer w = null; // Appending writer to the log file
        try {
            w = new OutputStreamWriter( new FileOutputStream( file, true ), Charset.forName( "UTF-8" ) );
            w.write( line );
            w.write( System.lineSeparator() );
            w.flush();
        } catch ( Exception e ) {
            System.out.println( "!! Could not write log file " + file + ": " + e.getMessage() );
        } finally {
            FileUtil.closeQuietly( w );
        }
    }
}
