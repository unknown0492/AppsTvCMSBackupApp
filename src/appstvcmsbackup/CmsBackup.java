package appstvcmsbackup;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * @description Zips the CMS folder (e.g. htdocs/7on7) to the backup drive and proves the zip is good:
 *              while zipping, a CRC32 + size of every source file is recorded; afterwards the zip is
 *              re-opened from the drive and every entry is decompressed and compared with that record.
 *              Only then is the .partial renamed to its final name and its .sha256 written.
 * @author Sohail
 */
public final class CmsBackup {

    // Extensions that are already compressed: stored with level 0 to save CPU time on old PCs
    private static final Set<String> COMPRESSED_EXT = new HashSet<String>( Arrays.asList(
            "jpg", "jpeg", "png", "gif", "webp", "mp4", "mkv", "avi", "mov", "wmv", "flv", "m4v", "ts",
            "mp3", "aac", "m4a", "ogg", "zip", "gz", "rar", "7z", "apk", "woff", "woff2", "pdf" ) );
    // Largest file a FAT32 drive can hold
    private static final long FAT32_MAX_FILE = 4L * 1024 * 1024 * 1024 - 1;
    // Attempts to open a source file that is temporarily locked
    private static final int OPEN_ATTEMPTS = 3;

    /**
     * @description Prevents instantiation
     */
    private CmsBackup() {
    }

    /**
     * @description Regex matching this app's verified CMS zips, e.g. cms_2026-11-08_020000.zip
     * @return Pattern
     */
    public static Pattern namePattern() {
        return Pattern.compile( "cms_(\\d{4}-\\d{2}-\\d{2})_\\d{6}\\.zip" );
    }

    /**
     * @description Regex matching v1 CMS backup folders (DD-MM-YYYY)
     * @return Pattern
     */
    public static Pattern legacyPattern() {
        return Pattern.compile( "\\d{2}-\\d{2}-\\d{4}" );
    }

    /**
     * @description Decides whether a CMS backup is due: due when no verified zip exists or the newest one is
     *              at least cms_backup_frequency_days old. Based on what is actually on the drive, so a PC that
     *              was switched off on the scheduled day catches up on the next run.
     * @param cfg Configuration
     * @param dir CMS backup folder
     * @return String reason it is NOT due, or null when a backup is due
     */
    public static String notDueReason( Config cfg, File dir ) {
        List<File> backups = Retention.verifiedBackups( dir, namePattern() ); // Newest first
        if ( backups.isEmpty() ) {
            return null;
        }
        Matcher m = namePattern().matcher( backups.get( 0 ).getName() ); // Extracts the date
        if ( !m.matches() ) {
            return null;
        }
        long age = ChronoUnit.DAYS.between( LocalDate.parse( m.group( 1 ) ), LocalDate.now() ); // Days since newest backup
        if ( age >= cfg.cmsFrequencyDays ) {
            return null;
        }
        return "CMS backup not due: newest verified backup " + backups.get( 0 ).getName() + " is " + age
                + " day(s) old (frequency " + cfg.cmsFrequencyDays + " days)";
    }

    /**
     * @description Runs the CMS backup
     * @param cfg Configuration
     * @param dir CMS backup folder on the backup drive
     * @return BackupResult outcome (never throws)
     */
    public static BackupResult run( Config cfg, File dir ) {
        BackupResult res = new BackupResult( BackupResult.TYPE_CMS ); // Outcome being built
        File partial = null; // Zip being written
        try {
            File source = new File( cfg.cmsSourceDir ).getAbsoluteFile(); // Folder to back up
            if ( !source.isDirectory() ) {
                throw new BackupException( "CMS source folder not found: " + source );
            }

            // 1. Inventory the source
            List<Path> files = new ArrayList<Path>(); // Files to zip
            List<Path> dirs = new ArrayList<Path>(); // Folders (so empty folders are restored too)
            long totalBytes = scan( source.toPath(), files, dirs ); // Sum of file sizes
            if ( files.isEmpty() ) {
                throw new BackupException( "CMS source folder " + source + " is empty - refusing to replace good backups with an empty one" );
            }

            // 2. Space and file-system checks
            String fsType = ""; // e.g. NTFS, FAT32, exFAT
            try {
                fsType = Files.getFileStore( dir.toPath() ).type();
            } catch ( Exception ignored ) {
                // Unknown file system: skip the FAT32 check
            }
            if ( fsType.toUpperCase().startsWith( "FAT" ) && totalBytes > FAT32_MAX_FILE ) {
                throw new BackupException( "CMS folder is " + FileUtil.human( totalBytes ) + " but the backup drive is " + fsType
                        + " (4 GB file limit). Reformat the thumb drive as NTFS or exFAT." );
            }
            long needed = totalBytes + cfg.minFreeSpaceMb * 1024 * 1024; // Worst case: nothing compresses
            long free = dir.getUsableSpace(); // Free bytes on the drive
            if ( free < needed ) {
                throw new BackupException( "Not enough space on backup drive: " + FileUtil.human( free ) + " free, up to "
                        + FileUtil.human( needed ) + " needed for " + files.size() + " files" );
            }

            // 3. Write the zip
            String stamp = new SimpleDateFormat( "yyyy-MM-dd_HHmmss" ).format( new Date( res.startedAt ) ); // Timestamp in the name
            String finalName = "cms_" + stamp + ".zip"; // Name after verification
            partial = new File( dir, finalName + FileUtil.PARTIAL_SUFFIX );
            Log.info( "Zipping " + files.size() + " files (" + FileUtil.human( totalBytes ) + ") from " + source + " to " + partial );
            Map<String, long[]> manifest = writeZip( source.toPath(), files, dirs, partial ); // entry -> {size, crc}

            // 4. Verify by reading the zip back from the drive
            Log.info( "Verifying zip (" + manifest.size() + " entries)..." );
            verifyZip( partial, manifest );
            String sha = FileUtil.sha256( partial ); // Checksum of the verified zip

            long size = partial.length(); // Final zip size
            List<File> existing = Retention.verifiedBackups( dir, namePattern() ); // Previous verified zips
            if ( !existing.isEmpty() && size < existing.get( 0 ).length() / 2 ) {
                res.warnings.add( "Zip is much smaller than the previous backup (" + FileUtil.human( size ) + " vs "
                        + FileUtil.human( existing.get( 0 ).length() ) + ") - check whether CMS files were deleted" );
            }

            // 5. Publish: checksum file first, then rename .partial -> final name
            FileUtil.writeShaSidecar( dir, finalName, sha );
            FileUtil.moveReplace( partial, new File( dir, finalName ) );
            partial = null;

            res.fileName = finalName;
            res.sizeBytes = size;
            res.sha256 = sha;
            res.itemCount = files.size();
            res.driveFreeBytes = dir.getUsableSpace();
            return res.succeed( "CMS backup verified: " + finalName + ", " + files.size() + " files, "
                    + FileUtil.human( totalBytes ) + " -> " + FileUtil.human( size ) );
        } catch ( BackupException e ) {
            res.driveFreeBytes = dir.getUsableSpace();
            return res.fail( e.getMessage() );
        } catch ( Throwable t ) {
            Log.error( "Unexpected error during CMS backup", t );
            res.driveFreeBytes = dir.getUsableSpace();
            return res.fail( "Unexpected error: " + t );
        } finally {
            if ( partial != null && partial.exists() && !partial.delete() ) {
                Log.warn( "Could not delete failed zip " + partial + " (will be cleaned next run)" );
            }
        }
    }

    /**
     * @description Walks the source tree (without following links) and collects files and folders
     * @param root  Source root
     * @param files Output: files
     * @param dirs  Output: sub-folders
     * @return long total size of all files
     * @throws BackupException when a folder cannot be read
     */
    private static long scan( Path root, final List<Path> files, final List<Path> dirs ) throws BackupException {
        final long[] total = { 0 }; // Running size
        final List<String> unreadable = new ArrayList<String>(); // Paths that could not be listed
        try {
            Files.walkFileTree( root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory( Path d, BasicFileAttributes attrs ) {
                    if ( !d.equals( root ) ) {
                        dirs.add( d );
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile( Path f, BasicFileAttributes attrs ) {
                    if ( attrs.isRegularFile() ) {
                        files.add( f );
                        total[ 0 ] += attrs.size();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed( Path f, IOException exc ) {
                    unreadable.add( f + " (" + exc.getMessage() + ")" );
                    return FileVisitResult.CONTINUE;
                }
            } );
        } catch ( IOException e ) {
            throw new BackupException( "Cannot read CMS source folder: " + e.getMessage(), e );
        }
        if ( !unreadable.isEmpty() ) {
            throw new BackupException( "Cannot read " + unreadable.size() + " item(s) in the CMS folder, e.g. " + unreadable.get( 0 ) );
        }
        Collections.sort( files );
        Collections.sort( dirs );
        return total[ 0 ];
    }

    /**
     * @description Converts a source path to its zip entry name: "<source folder name>/<relative path>"
     * @param root Source root
     * @param p    Path inside root
     * @return String entry name with forward slashes
     */
    private static String entryName( Path root, Path p ) {
        String rel = root.relativize( p ).toString().replace( '\\', '/' ); // Relative path
        return root.getFileName().toString() + "/" + rel;
    }

    /**
     * @description Writes the zip, recording size and CRC32 of every source file as it is read
     * @param root   Source root
     * @param files  Files to add
     * @param dirs   Folders to add
     * @param target .partial zip file
     * @return Map entry name -> {size, crc}
     * @throws Exception on any read/write error
     */
    private static Map<String, long[]> writeZip( Path root, List<Path> files, List<Path> dirs, File target ) throws Exception {
        Map<String, long[]> manifest = new LinkedHashMap<String, long[]>(); // What the zip must contain
        FileOutputStream fos = new FileOutputStream( target ); // Raw file (kept for fsync)
        ZipOutputStream zos = new ZipOutputStream( new BufferedOutputStream( fos, 1024 * 1024 ) ); // Zip writer
        try {
            for ( Path d : dirs ) {
                String name = entryName( root, d ) + "/"; // Folder entry
                ZipEntry ze = new ZipEntry( name );
                ze.setTime( d.toFile().lastModified() );
                zos.putNextEntry( ze );
                zos.closeEntry();
                manifest.put( name, new long[]{ 0, 0 } );
            }
            byte[] buf = new byte[ 256 * 1024 ]; // Copy buffer
            int done = 0; // Files written so far
            for ( Path f : files ) {
                String name = entryName( root, f ); // File entry
                String lower = name.toLowerCase(); // For extension check
                String ext = lower.contains( "." ) ? lower.substring( lower.lastIndexOf( '.' ) + 1 ) : ""; // Extension
                zos.setLevel( COMPRESSED_EXT.contains( ext ) ? 0 : 6 );
                ZipEntry ze = new ZipEntry( name );
                ze.setTime( f.toFile().lastModified() );
                zos.putNextEntry( ze );
                CRC32 crc = new CRC32(); // Checksum of the source bytes
                long size = 0; // Bytes read from the source
                InputStream in = openWithRetry( f.toFile() ); // Source file
                try {
                    int n; // Bytes read
                    while ( ( n = in.read( buf ) ) > 0 ) {
                        zos.write( buf, 0, n );
                        crc.update( buf, 0, n );
                        size += n;
                    }
                } finally {
                    in.close();
                }
                zos.closeEntry();
                manifest.put( name, new long[]{ size, crc.getValue() } );
                done++;
                if ( done % 2000 == 0 ) {
                    Log.info( "  ... " + done + " / " + files.size() + " files" );
                }
            }
            zos.finish();
            zos.flush();
            fos.getFD().sync(); // Force data onto the thumb drive, not just the Windows cache
        } finally {
            FileUtil.closeQuietly( zos );
        }
        return manifest;
    }

    /**
     * @description Opens a file, retrying briefly if it is locked by another process
     * @param f File
     * @return InputStream
     * @throws BackupException when it cannot be opened
     */
    private static InputStream openWithRetry( File f ) throws BackupException {
        IOException last = null; // Last error
        for ( int i = 1; i <= OPEN_ATTEMPTS; i++ ) {
            try {
                return new FileInputStream( f );
            } catch ( IOException e ) {
                last = e;
                try {
                    Thread.sleep( 2000 );
                } catch ( InterruptedException ie ) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        throw new BackupException( "Cannot read CMS file " + f + ": " + ( last == null ? "" : last.getMessage() ) );
    }

    /**
     * @description Re-opens the zip from disk and checks every entry decompresses to the recorded size and CRC
     * @param zip      Zip file
     * @param manifest Expected entries
     * @return void
     * @throws BackupException on any mismatch or corruption
     */
    private static void verifyZip( File zip, Map<String, long[]> manifest ) throws BackupException {
        ZipFile zf = null; // Zip reader (uses the central directory, supports ZIP64)
        try {
            zf = new ZipFile( zip );
            int seen = 0; // Entries checked
            byte[] buf = new byte[ 256 * 1024 ]; // Read buffer
            Enumeration<? extends ZipEntry> en = zf.entries(); // All entries
            while ( en.hasMoreElements() ) {
                ZipEntry ze = en.nextElement(); // Current entry
                long[] expected = manifest.get( ze.getName() ); // {size, crc}
                if ( expected == null ) {
                    throw new BackupException( "Zip contains an unexpected entry: " + ze.getName() );
                }
                seen++;
                if ( ze.isDirectory() ) {
                    continue;
                }
                CRC32 crc = new CRC32(); // Checksum of decompressed bytes
                long size = 0; // Decompressed size
                InputStream in = zf.getInputStream( ze ); // Entry data
                try {
                    int n; // Bytes read
                    while ( ( n = in.read( buf ) ) > 0 ) {
                        crc.update( buf, 0, n );
                        size += n;
                    }
                } finally {
                    in.close();
                }
                if ( size != expected[ 0 ] || crc.getValue() != expected[ 1 ] ) {
                    throw new BackupException( "Zip entry is corrupt: " + ze.getName() );
                }
            }
            if ( seen != manifest.size() ) {
                throw new BackupException( "Zip has " + seen + " entries, expected " + manifest.size() );
            }
        } catch ( BackupException e ) {
            throw e;
        } catch ( Exception e ) {
            throw new BackupException( "Zip failed verification (corrupt or unreadable): " + e.getMessage(), e );
        } finally {
            FileUtil.closeQuietly( zf );
        }
    }
}
