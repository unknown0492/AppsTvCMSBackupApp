package appstvcmsbackup;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;

/**
 * @description File helpers: durable writes, atomic rename, hashing, recursive delete.
 * @author Sohail
 */
public final class FileUtil {

    /** UTF-8 charset used for every text file this app writes */
    public static final Charset UTF8 = Charset.forName( "UTF-8" );
    /** Suffix of a file that is still being written / not yet verified */
    public static final String PARTIAL_SUFFIX = ".partial";
    /** Suffix of the checksum file written next to every verified backup */
    public static final String SHA_SUFFIX = ".sha256";

    /**
     * @description Prevents instantiation
     */
    private FileUtil() {
    }

    /**
     * @description Closes a stream, ignoring errors (for finally blocks)
     * @param c Stream or writer (may be null)
     * @return void
     */
    public static void closeQuietly( Closeable c ) {
        if ( c == null ) {
            return;
        }
        try {
            c.close();
        } catch ( IOException ignored ) {
            // Nothing useful to do here
        }
    }

    /**
     * @description Reads a whole text file as UTF-8 (strips a leading BOM if Notepad added one)
     * @param file File to read
     * @return String file contents
     * @throws IOException when the file cannot be read
     */
    public static String readText( File file ) throws IOException {
        byte[] bytes = Files.readAllBytes( file.toPath() ); // Raw content
        String s = new String( bytes, UTF8 ); // Decoded content
        if ( s.length() > 0 && s.charAt( 0 ) == '﻿' ) {
            s = s.substring( 1 );
        }
        return s;
    }

    /**
     * @description Writes a small text file durably: write to temp, fsync, then rename over the target
     * @param file    Target file
     * @param content Text to write (UTF-8)
     * @return void
     * @throws IOException when writing fails
     */
    public static void writeTextDurable( File file, String content ) throws IOException {
        File tmp = new File( file.getParentFile(), file.getName() + ".tmp" ); // Temp file beside the target
        FileOutputStream fos = new FileOutputStream( tmp );
        try {
            fos.write( content.getBytes( UTF8 ) );
            fos.flush();
            fos.getFD().sync();
        } finally {
            fos.close();
        }
        moveReplace( tmp, file );
    }

    /**
     * @description Renames a file, atomically where the file system supports it, replacing the target
     * @param from Source file
     * @param to   Target file
     * @return void
     * @throws IOException when the rename fails
     */
    public static void moveReplace( File from, File to ) throws IOException {
        try {
            Files.move( from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING );
        } catch ( IOException atomicFailed ) {
            Files.move( from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING );
        }
    }

    /**
     * @description Computes the SHA-256 of a file
     * @param file File to hash
     * @return String lowercase hex digest
     * @throws IOException when the file cannot be read
     */
    public static String sha256( File file ) throws IOException {
        MessageDigest md = newSha256(); // Digest accumulator
        InputStream in = new FileInputStream( file );
        try {
            byte[] buf = new byte[ 1024 * 1024 ]; // 1 MB read buffer
            int n; // Bytes read in this iteration
            while ( ( n = in.read( buf ) ) > 0 ) {
                md.update( buf, 0, n );
            }
        } finally {
            in.close();
        }
        return toHex( md.digest() );
    }

    /**
     * @description Creates a SHA-256 MessageDigest (always available on every JRE)
     * @return MessageDigest
     */
    public static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance( "SHA-256" );
        } catch ( Exception e ) {
            throw new IllegalStateException( "SHA-256 not available", e );
        }
    }

    /**
     * @description Converts bytes to lowercase hex
     * @param bytes Bytes
     * @return String hex
     */
    public static String toHex( byte[] bytes ) {
        StringBuilder sb = new StringBuilder( bytes.length * 2 ); // Output buffer
        for ( byte b : bytes ) {
            sb.append( String.format( "%02x", b & 0xff ) );
        }
        return sb.toString();
    }

    /**
     * @description Writes the "<hash> *<name>" checksum file next to a backup (standard sha256sum format,
     *              so it can be checked with any sha256sum tool). Its presence marks the backup as verified.
     * @param backupFileName Final name of the backup file
     * @param dir            Directory holding the backup
     * @param hash           SHA-256 hex
     * @return void
     * @throws IOException when writing fails
     */
    public static void writeShaSidecar( File dir, String backupFileName, String hash ) throws IOException {
        writeTextDurable( new File( dir, backupFileName + SHA_SUFFIX ), hash + " *" + backupFileName + "\n" );
    }

    /**
     * @description Deletes a file or directory tree. Does not follow symlinks / junctions.
     * @param target File or directory
     * @return void
     * @throws IOException when something cannot be deleted
     */
    public static void deleteRecursive( File target ) throws IOException {
        if ( !target.exists() ) {
            return;
        }
        Files.walkFileTree( target.toPath(), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile( Path file, BasicFileAttributes attrs ) throws IOException {
                file.toFile().setWritable( true );
                Files.delete( file );
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory( Path dir, IOException exc ) throws IOException {
                if ( exc != null ) {
                    throw exc;
                }
                Files.delete( dir );
                return FileVisitResult.CONTINUE;
            }
        } );
    }

    /**
     * @description Reads everything from a stream into memory, keeping at most maxBytes (the rest is discarded)
     * @param in       Stream to drain
     * @param maxBytes Maximum bytes to keep
     * @return String captured text (UTF-8)
     * @throws IOException on read error
     */
    public static String drain( InputStream in, int maxBytes ) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); // Captured bytes
        byte[] buf = new byte[ 8192 ]; // Read buffer
        int n; // Bytes read in this iteration
        while ( ( n = in.read( buf ) ) > 0 ) {
            int room = maxBytes - out.size(); // How much more we are allowed to keep
            if ( room > 0 ) {
                out.write( buf, 0, Math.min( n, room ) );
            }
        }
        return new String( out.toByteArray(), UTF8 );
    }

    /**
     * @description Formats a byte count for humans (e.g. 1.5 GB)
     * @param bytes Byte count
     * @return String formatted size
     */
    public static String human( long bytes ) {
        if ( bytes < 1024 ) {
            return bytes + " B";
        }
        String[] units = { "KB", "MB", "GB", "TB" }; // Unit labels
        double v = bytes; // Value being scaled down
        int i = -1; // Index into units
        while ( v >= 1024 && i < units.length - 1 ) {
            v /= 1024;
            i++;
        }
        return String.format( "%.1f %s", v, units[ i ] );
    }
}
