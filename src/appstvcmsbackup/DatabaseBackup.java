package appstvcmsbackup;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * @description Dumps the MySQL database to a gzip file on the backup drive and proves the dump is complete:
 *              - mysqldump exit code must be 0
 *              - the gzip file must decompress fully (CRC check)
 *              - the dump must end with mysqldump's "-- Dump completed" line
 *              - the number of CREATE TABLE statements must equal the table count in information_schema
 *              Only then is the file renamed from .partial to its final name and its .sha256 written.
 * @author Sohail
 */
public final class DatabaseBackup {

    // Maximum time mysqldump may run before it is killed
    private static final long DUMP_TIMEOUT_MINUTES = 180;
    // Attempts to reach MySQL (it may still be starting right after boot)
    private static final int CONNECT_ATTEMPTS = 3;
    // Wait between connection attempts
    private static final long CONNECT_RETRY_MS = 30000;

    /**
     * @description Prevents instantiation
     */
    private DatabaseBackup() {
    }

    /**
     * @description Regex matching this app's verified dump names, e.g. appstv2_2026-11-08_020000.sql.gz
     * @param cfg Configuration
     * @return Pattern
     */
    public static Pattern namePattern( Config cfg ) {
        return Pattern.compile( Pattern.quote( cfg.dbName ) + "_\\d{4}-\\d{2}-\\d{2}_\\d{6}\\.sql\\.gz" );
    }

    /**
     * @description Regex matching v1 dump names (DD-MM-YYYY.sql)
     * @return Pattern
     */
    public static Pattern legacyPattern() {
        return Pattern.compile( "\\d{2}-\\d{2}-\\d{4}\\.sql" );
    }

    /**
     * @description Runs the database backup
     * @param cfg   Configuration
     * @param dir   Database backup folder on the backup drive
     * @param force Back up even when a verified dump from today already exists
     * @return BackupResult outcome (never throws)
     */
    public static BackupResult run( Config cfg, File dir, boolean force ) {
        BackupResult res = new BackupResult( BackupResult.TYPE_DB ); // Outcome being built
        File cnf = null; // Temporary MySQL credentials file
        File partial = null; // Dump being written
        try {
            String today = new SimpleDateFormat( "yyyy-MM-dd" ).format( new Date() ); // Date part of the file name
            List<File> existing = Retention.verifiedBackups( dir, namePattern( cfg ) ); // Previous verified dumps
            if ( !force ) {
                for ( File f : existing ) {
                    if ( f.getName().startsWith( cfg.dbName + "_" + today + "_" ) ) {
                        return res.skip( "Database already backed up today (" + f.getName() + ")" );
                    }
                }
            }

            File mysqldump = new File( cfg.mysqlBinDir, "mysqldump.exe" ); // Dump tool
            File mysql = new File( cfg.mysqlBinDir, "mysql.exe" ); // Client used for table count
            if ( !mysqldump.isFile() || !mysql.isFile() ) {
                throw new BackupException( "mysqldump.exe / mysql.exe not found in " + cfg.mysqlBinDir + " - check mysql_bin_dir in config" );
            }

            cnf = writeCredentialsFile( cfg );

            // 1. Ask MySQL how many tables the database has and roughly how big it is
            long[] stats = queryStats( cfg, mysql, cnf ); // {tableCount, dataBytes}
            long expectedTables = stats[ 0 ]; // Tables the dump must contain
            if ( expectedTables == 0 ) {
                throw new BackupException( "Database '" + cfg.dbName + "' has no tables or does not exist" );
            }

            // 2. Make sure the dump will fit (gzip dumps are usually far below the raw data size)
            long needed = cfg.minFreeSpaceMb * 1024 * 1024 + (long) ( stats[ 1 ] * 0.6 ); // Conservative estimate
            long free = dir.getUsableSpace(); // Free bytes on the drive
            if ( free < needed ) {
                throw new BackupException( "Not enough space on backup drive: " + FileUtil.human( free ) + " free, about "
                        + FileUtil.human( needed ) + " needed" );
            }

            // 3. Dump straight into a gzip .partial file
            String stamp = new SimpleDateFormat( "yyyy-MM-dd_HHmmss" ).format( new Date( res.startedAt ) ); // Timestamp in the name
            String finalName = cfg.dbName + "_" + stamp + ".sql.gz"; // Name after verification
            partial = new File( dir, finalName + FileUtil.PARTIAL_SUFFIX );
            Log.info( "Dumping database '" + cfg.dbName + "' (" + expectedTables + " tables, ~" + FileUtil.human( stats[ 1 ] ) + ") to " + partial );
            String stderr = dump( cfg, mysqldump, cnf, partial ); // mysqldump warnings (exit code was 0)
            if ( !stderr.trim().isEmpty() ) {
                res.warnings.add( "mysqldump reported: " + stderr.trim() );
            }

            // 4. Read the file back from the drive and check it
            Log.info( "Verifying database dump..." );
            String[] check = verify( partial ); // {sha256, createTableCount, lastLine}
            long dumpedTables = Long.parseLong( check[ 1 ] ); // CREATE TABLE statements found
            if ( !check[ 2 ].startsWith( "-- Dump completed" ) ) {
                throw new BackupException( "Dump is incomplete: it does not end with '-- Dump completed' (last line: '" + check[ 2 ] + "')" );
            }
            if ( dumpedTables != expectedTables ) {
                throw new BackupException( "Dump contains " + dumpedTables + " tables but the database has " + expectedTables );
            }

            long size = partial.length(); // Final compressed size
            if ( !existing.isEmpty() ) {
                long prev = existing.get( 0 ).length(); // Size of the previous verified dump
                if ( size < prev / 2 ) {
                    res.warnings.add( "Dump is much smaller than the previous one (" + FileUtil.human( size ) + " vs "
                            + FileUtil.human( prev ) + ") - check whether data was deleted from the database" );
                }
            }

            // 5. Publish: checksum file first, then rename .partial -> final name
            FileUtil.writeShaSidecar( dir, finalName, check[ 0 ] );
            FileUtil.moveReplace( partial, new File( dir, finalName ) );
            partial = null;

            res.fileName = finalName;
            res.sizeBytes = size;
            res.sha256 = check[ 0 ];
            res.itemCount = dumpedTables;
            res.driveFreeBytes = dir.getUsableSpace();
            return res.succeed( "Database backup verified: " + finalName + ", " + dumpedTables + " tables, " + FileUtil.human( size ) );
        } catch ( BackupException e ) {
            res.driveFreeBytes = dir.getUsableSpace();
            return res.fail( e.getMessage() );
        } catch ( Throwable t ) {
            Log.error( "Unexpected error during database backup", t );
            res.driveFreeBytes = dir.getUsableSpace();
            return res.fail( "Unexpected error: " + t );
        } finally {
            if ( cnf != null && !cnf.delete() ) {
                cnf.deleteOnExit();
            }
            if ( partial != null && partial.exists() && !partial.delete() ) {
                Log.warn( "Could not delete failed dump " + partial + " (will be cleaned next run)" );
            }
        }
    }

    /**
     * @description Writes a temporary MySQL option file so the password never appears on the command line
     * @param cfg Configuration
     * @return File the option file (caller deletes it)
     * @throws Exception when it cannot be written
     */
    private static File writeCredentialsFile( Config cfg ) throws Exception {
        File cnf = File.createTempFile( "appstvbk", ".cnf" ); // Temp option file
        cnf.setReadable( false, false );
        cnf.setReadable( true, true );
        String pwd = cfg.dbPassword.replace( "\\", "\\\\" ).replace( "\"", "\\\"" ); // Escaped for option-file syntax
        String content = "[client]\r\n"
                + "user=\"" + cfg.dbUser.replace( "\"", "\\\"" ) + "\"\r\n"
                + "password=\"" + pwd + "\"\r\n"
                + "host=" + cfg.dbHost + "\r\n"
                + "port=" + cfg.dbPort + "\r\n"; // Option file text
        FileUtil.writeTextDurable( cnf, content );
        return cnf;
    }

    /**
     * @description Returns {base table count, data+index bytes} for the database, retrying while MySQL starts
     * @param cfg   Configuration
     * @param mysql mysql.exe
     * @param cnf   Credentials file
     * @return long[] {tables, bytes}
     * @throws BackupException when MySQL cannot be reached
     */
    private static long[] queryStats( Config cfg, File mysql, File cnf ) throws BackupException {
        String sql = "SELECT COUNT(*), IFNULL(SUM(DATA_LENGTH + INDEX_LENGTH), 0) FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = '" + cfg.dbName + "' AND TABLE_TYPE = 'BASE TABLE'"; // db_name is validated in Config
        String lastError = ""; // Error from the last failed attempt
        for ( int attempt = 1; attempt <= CONNECT_ATTEMPTS; attempt++ ) {
            try {
                List<String> cmd = new ArrayList<String>(); // mysql command line
                cmd.add( mysql.getAbsolutePath() );
                cmd.add( "--defaults-extra-file=" + cnf.getAbsolutePath() );
                cmd.add( "-N" );
                cmd.add( "-B" );
                cmd.add( "-e" );
                cmd.add( sql );
                String[] out = runCapture( cmd, 120 ); // {exitCode, stdout, stderr}
                if ( "0".equals( out[ 0 ] ) ) {
                    String[] parts = out[ 1 ].trim().split( "\\s+" ); // "count bytes"
                    return new long[]{ Long.parseLong( parts[ 0 ] ), Long.parseLong( parts[ 1 ] ) };
                }
                lastError = out[ 2 ].trim();
            } catch ( Exception e ) {
                lastError = e.toString();
            }
            Log.warn( "Cannot query MySQL (attempt " + attempt + "/" + CONNECT_ATTEMPTS + "): " + lastError );
            // ERROR 1045 = wrong user/password: MySQL is running, so waiting and retrying cannot help
            if ( lastError.contains( "ERROR 1045" ) ) {
                throw new BackupException( "MySQL rejected the login (wrong db_user/db_password in config"
                        + ( cfg.dbPassword.isEmpty() ? " - db_password is empty" : "" ) + "). Error: " + lastError );
            }
            if ( attempt < CONNECT_ATTEMPTS ) {
                sleep( CONNECT_RETRY_MS );
            }
        }
        throw new BackupException( "Cannot connect to MySQL - is it running? Check db_user/db_password in config. Error: " + lastError );
    }

    /**
     * @description Runs mysqldump with stdout streamed into a gzip file; fails on non-zero exit or timeout
     * @param cfg       Configuration
     * @param mysqldump mysqldump.exe
     * @param cnf       Credentials file
     * @param target    .partial gzip file to write
     * @return String stderr output (warnings) when mysqldump succeeded
     * @throws Exception when mysqldump fails
     */
    private static String dump( Config cfg, File mysqldump, File cnf, File target ) throws Exception {
        List<String> cmd = new ArrayList<String>(); // mysqldump command line
        cmd.add( mysqldump.getAbsolutePath() );
        cmd.add( "--defaults-extra-file=" + cnf.getAbsolutePath() ); // Must be the first option
        cmd.add( "--single-transaction" ); // Consistent snapshot for InnoDB without locking the CMS
        cmd.add( "--routines" );
        cmd.add( "--triggers" );
        cmd.add( "--events" );
        cmd.add( "--hex-blob" );
        cmd.add( "--max-allowed-packet=512M" );
        cmd.add( "--default-character-set=" + cfg.dbCharset );
        cmd.add( cfg.dbName );

        final Process p = new ProcessBuilder( cmd ).start(); // Running mysqldump
        final FileOutputStream fos = new FileOutputStream( target ); // Raw file (kept for fsync)
        final Exception[] pumpError = new Exception[ 1 ]; // Error from the stdout pump thread
        final String[] stderr = { "" }; // Captured stderr

        Thread pump = new Thread( () -> {
            OutputStream gz = null; // gzip writer
            try {
                gz = new GZIPOutputStream( new BufferedOutputStream( fos, 1024 * 1024 ), 64 * 1024 );
                InputStream in = p.getInputStream(); // mysqldump stdout
                byte[] buf = new byte[ 256 * 1024 ]; // Copy buffer
                int n; // Bytes read
                while ( ( n = in.read( buf ) ) > 0 ) {
                    gz.write( buf, 0, n );
                }
                ( (GZIPOutputStream) gz ).finish();
                gz.flush();
                fos.getFD().sync(); // Force data onto the thumb drive, not just the Windows cache
            } catch ( Exception e ) {
                pumpError[ 0 ] = e;
                p.destroyForcibly(); // Nobody reads stdout any more; stop mysqldump instead of letting it block
            } finally {
                FileUtil.closeQuietly( gz );
                FileUtil.closeQuietly( fos );
            }
        }, "dump-stdout" );
        Thread err = new Thread( () -> {
            try {
                stderr[ 0 ] = FileUtil.drain( p.getErrorStream(), 16 * 1024 );
            } catch ( Exception ignored ) {
                // Process died; exit code tells us what happened
            }
        }, "dump-stderr" );
        pump.start();
        err.start();

        boolean finished = p.waitFor( DUMP_TIMEOUT_MINUTES, TimeUnit.MINUTES ); // false = timed out
        if ( !finished ) {
            p.destroyForcibly();
        }
        pump.join( 60000 );
        err.join( 10000 );

        if ( !finished ) {
            throw new BackupException( "mysqldump did not finish within " + DUMP_TIMEOUT_MINUTES + " minutes and was stopped" );
        }
        if ( pumpError[ 0 ] != null ) {
            throw new BackupException( "Writing the dump to the backup drive failed: " + pumpError[ 0 ].getMessage(), pumpError[ 0 ] );
        }
        if ( p.exitValue() != 0 ) {
            throw new BackupException( "mysqldump failed (exit code " + p.exitValue() + "): " + stderr[ 0 ].trim() );
        }
        return stderr[ 0 ];
    }

    /**
     * @description Reads the gzip dump back from disk: validates gzip CRC, hashes the file, counts
     *              CREATE TABLE lines and returns the last non-empty line. Memory use is constant
     *              regardless of line length.
     * @param file Dump file
     * @return String[] {sha256, createTableCount, lastNonEmptyLine}
     * @throws BackupException when the file is corrupt
     */
    private static String[] verify( File file ) throws BackupException {
        MessageDigest md = FileUtil.newSha256(); // Hash of the compressed file
        InputStream in = null; // Decompressing reader
        try {
            in = new GZIPInputStream( new BufferedInputStream( new DigestInputStream( new FileInputStream( file ), md ), 1024 * 1024 ), 64 * 1024 );
            byte[] marker = "CREATE TABLE `".getBytes( FileUtil.UTF8 ); // Start of a table definition
            byte[] head = new byte[ 64 ]; // First bytes of the current line
            int headLen = 0; // Bytes in head
            String lastLine = ""; // Last non-empty line seen (prefix)
            long tables = 0; // CREATE TABLE count
            byte[] buf = new byte[ 256 * 1024 ]; // Read buffer
            int n; // Bytes read
            while ( ( n = in.read( buf ) ) > 0 ) {
                for ( int i = 0; i < n; i++ ) {
                    byte b = buf[ i ]; // Current byte
                    if ( b == '\n' ) {
                        if ( startsWith( head, headLen, marker ) ) {
                            tables++;
                        }
                        String line = new String( head, 0, headLen, FileUtil.UTF8 ).trim(); // Line prefix
                        if ( !line.isEmpty() ) {
                            lastLine = line;
                        }
                        headLen = 0;
                    } else if ( headLen < head.length ) {
                        head[ headLen++ ] = b;
                    }
                }
            }
            if ( headLen > 0 ) {
                String line = new String( head, 0, headLen, FileUtil.UTF8 ).trim(); // Final line without newline
                if ( !line.isEmpty() ) {
                    lastLine = line;
                }
            }
            in.close();
            in = null;
            return new String[]{ FileUtil.toHex( md.digest() ), String.valueOf( tables ), lastLine };
        } catch ( Exception e ) {
            throw new BackupException( "Dump file failed verification (corrupt or unreadable): " + e.getMessage(), e );
        } finally {
            FileUtil.closeQuietly( in );
        }
    }

    /**
     * @description True when the first len bytes of buf begin with prefix
     * @param buf    Buffer
     * @param len    Valid bytes in buf
     * @param prefix Prefix to test
     * @return boolean
     */
    private static boolean startsWith( byte[] buf, int len, byte[] prefix ) {
        if ( len < prefix.length ) {
            return false;
        }
        for ( int i = 0; i < prefix.length; i++ ) {
            if ( buf[ i ] != prefix[ i ] ) {
                return false;
            }
        }
        return true;
    }

    /**
     * @description Runs a short command and captures its output
     * @param cmd            Command line
     * @param timeoutSeconds Maximum run time
     * @return String[] {exitCode, stdout, stderr}
     * @throws Exception on start failure or timeout
     */
    private static String[] runCapture( List<String> cmd, long timeoutSeconds ) throws Exception {
        final Process p = new ProcessBuilder( cmd ).start(); // Running command
        final String[] out = { "", "" }; // {stdout, stderr}
        Thread t1 = new Thread( () -> {
            try {
                out[ 0 ] = FileUtil.drain( p.getInputStream(), 64 * 1024 );
            } catch ( Exception ignored ) {
                // Exit code tells the story
            }
        } );
        Thread t2 = new Thread( () -> {
            try {
                out[ 1 ] = FileUtil.drain( p.getErrorStream(), 16 * 1024 );
            } catch ( Exception ignored ) {
                // Exit code tells the story
            }
        } );
        t1.start();
        t2.start();
        if ( !p.waitFor( timeoutSeconds, TimeUnit.SECONDS ) ) {
            p.destroyForcibly();
            throw new BackupException( "Command timed out: " + cmd.get( 0 ) );
        }
        t1.join( 5000 );
        t2.join( 5000 );
        return new String[]{ String.valueOf( p.exitValue() ), out[ 0 ], out[ 1 ] };
    }

    /**
     * @description Sleeps, ignoring interruption
     * @param ms Milliseconds
     * @return void
     */
    private static void sleep( long ms ) {
        try {
            Thread.sleep( ms );
        } catch ( InterruptedException ignored ) {
            Thread.currentThread().interrupt();
        }
    }
}
