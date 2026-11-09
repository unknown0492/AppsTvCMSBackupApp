package appstvcmsbackup;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * @description Sends backup reports to the cloud webservice (store_backup_report).
 *              Every report is first saved to the local "outbox" folder and only deleted after the server
 *              confirms it, so reports made while the internet is down are delivered on a later run.
 *              Each report carries a unique report_id so a resend never creates a duplicate on the server.
 *              Network calls have hard timeouts and can never block a backup.
 * @author Sohail
 */
public class StatusReporter {

    // Connect timeout for the webservice
    private static final int CONNECT_TIMEOUT_MS = 15000;
    // Read timeout for the webservice
    private static final int READ_TIMEOUT_MS = 30000;
    // Reports older than this are dropped from the outbox
    private static final long MAX_AGE_MS = 30L * 24 * 3600 * 1000;
    // Longest message sent to the server
    private static final int MAX_MESSAGE = 4000;

    // Configuration
    private final Config cfg;
    // Local folder of reports not yet confirmed by the server
    private final File outbox;

    /**
     * @description Creates the reporter
     * @param cfg    Configuration
     * @param appDir Application folder (outbox is created inside it)
     */
    public StatusReporter( Config cfg, File appDir ) {
        this.cfg = cfg;
        this.outbox = new File( appDir, "outbox" );
        if ( !outbox.exists() ) {
            outbox.mkdirs();
        }
    }

    /**
     * @description Queues a report for a backup result and tries to deliver everything in the outbox
     * @param r Result to report
     * @return void
     */
    public void report( BackupResult r ) {
        JSONObject json = new JSONObject(); // Report fields (become POST parameters)
        json.put( "what_do_you_want", "store_backup_report" );
        json.put( "report_id", UUID.randomUUID().toString() );
        json.put( "hotel_id", cfg.hotelId );
        json.put( "hotel_name", cfg.hotelName );
        json.put( "support_email", cfg.supportEmail );
        json.put( "backup_type", r.type );
        json.put( "status", r.status );
        json.put( "date", new SimpleDateFormat( "dd-MM-yyyy" ).format( new Date( r.startedAt ) ) ); // Same format as v1 logs
        json.put( "started_at", String.valueOf( r.startedAt ) );
        json.put( "finished_at", String.valueOf( r.finishedAt == 0 ? System.currentTimeMillis() : r.finishedAt ) );
        json.put( "file_name", r.fileName );
        json.put( "size_bytes", String.valueOf( r.sizeBytes ) );
        json.put( "sha256", r.sha256 );
        json.put( "item_count", String.valueOf( r.itemCount ) );
        json.put( "drive_free_bytes", String.valueOf( r.driveFreeBytes ) );
        json.put( "cms_frequency_days", String.valueOf( cfg.cmsFrequencyDays ) );
        json.put( "app_version", AppsTvCMSBackup.VERSION );
        String msg = r.message == null ? "" : r.message; // Message (truncated)
        json.put( "message", msg.length() > MAX_MESSAGE ? msg.substring( 0, MAX_MESSAGE ) : msg );
        json.put( "queued_at", String.valueOf( System.currentTimeMillis() ) );

        File f = new File( outbox, System.currentTimeMillis() + "_" + r.type + "_" + json.get( "report_id" ) + ".json" ); // Outbox file
        try {
            FileUtil.writeTextDurable( f, json.toJSONString() );
        } catch ( Exception e ) {
            Log.error( "Could not queue report in " + outbox + "; sending directly", e );
            send( json );
            return;
        }
        flush();
    }

    /**
     * @description Delivers queued reports oldest-first; stops at the first network failure (tries again next run)
     * @return void
     */
    public void flush() {
        File[] files = outbox.listFiles( ( d, n ) -> n.endsWith( ".json" ) ); // Pending reports
        if ( files == null || files.length == 0 ) {
            return;
        }
        Arrays.sort( files );
        for ( File f : files ) {
            JSONObject json; // Pending report
            try {
                json = (JSONObject) new JSONParser().parse( FileUtil.readText( f ) );
            } catch ( Exception e ) {
                Log.warn( "Dropping unreadable queued report " + f.getName() );
                f.delete();
                continue;
            }
            long queued = parseLong( json.get( "queued_at" ) ); // When it was queued
            if ( System.currentTimeMillis() - queued > MAX_AGE_MS ) {
                Log.warn( "Dropping report older than 30 days: " + f.getName() );
                f.delete();
                continue;
            }
            String outcome = send( json ); // "ok", "rejected" or "network"
            if ( "network".equals( outcome ) ) {
                Log.warn( "Webservice unreachable; queued report(s) will be sent on the next run" );
                return;
            }
            if ( "ok".equals( outcome ) ) {
                f.delete();
            }
            // "rejected" (bad key, unknown hotel, server not updated yet): keep it, retried until it expires
        }
    }

    /**
     * @description POSTs one report
     * @param json Report fields
     * @return String "ok" (stored), "rejected" (server refused permanently) or "network" (try later)
     */
    private String send( JSONObject json ) {
        HttpURLConnection con = null; // HTTP connection
        try {
            StringBuilder body = new StringBuilder(); // Form-encoded body
            List<Object> keys = new ArrayList<Object>( json.keySet() ); // Parameter names
            keys.add( "key" );
            for ( Object k : keys ) {
                Object v = "key".equals( k ) ? cfg.accessKey : json.get( k ); // Value
                if ( "queued_at".equals( k ) ) {
                    continue;
                }
                if ( body.length() > 0 ) {
                    body.append( '&' );
                }
                body.append( URLEncoder.encode( k.toString(), "UTF-8" ) ).append( '=' )
                        .append( URLEncoder.encode( v == null ? "" : v.toString(), "UTF-8" ) );
            }
            byte[] data = body.toString().getBytes( FileUtil.UTF8 ); // Request bytes

            con = (HttpURLConnection) new URL( cfg.urlWebservice ).openConnection();
            con.setConnectTimeout( CONNECT_TIMEOUT_MS );
            con.setReadTimeout( READ_TIMEOUT_MS );
            con.setDoOutput( true );
            con.setRequestMethod( "POST" );
            con.setRequestProperty( "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8" );
            con.setRequestProperty( "User-Agent", "AppsTvCMSBackup/" + AppsTvCMSBackup.VERSION );
            con.setFixedLengthStreamingMode( data.length );
            OutputStream os = con.getOutputStream(); // Request body stream
            os.write( data );
            os.close();

            int code = con.getResponseCode(); // HTTP status
            InputStream in = code < 400 ? con.getInputStream() : con.getErrorStream(); // Response body stream
            String resp = in == null ? "" : FileUtil.drain( in, 16 * 1024 ); // Response text
            if ( code != 200 ) {
                Log.warn( "Webservice HTTP " + code + " for report " + json.get( "report_id" ) );
                return "network";
            }
            String[] parsed = parseResponse( resp ); // {type, info}
            if ( "success".equals( parsed[ 0 ] ) ) {
                Log.info( "Reported [" + json.get( "backup_type" ) + "] " + json.get( "status" ) + " to cloud: " + parsed[ 1 ] );
                return "ok";
            }
            if ( parsed[ 0 ] == null ) {
                Log.warn( "Unexpected webservice response (will retry): " + resp );
                return "network";
            }
            Log.error( "Webservice rejected the report (kept in outbox, retried next run): " + parsed[ 1 ], null );
            return "rejected";
        } catch ( Exception e ) {
            Log.warn( "Could not reach webservice " + cfg.urlWebservice + ": " + e );
            return "network";
        } finally {
            if ( con != null ) {
                con.disconnect();
            }
        }
    }

    /**
     * @description Parses the framework's standard response: [{"type":"success|error","for":"...","info":"..."}]
     * @param resp Response text
     * @return String[] {type or null when unparseable, info}
     */
    private static String[] parseResponse( String resp ) {
        try {
            Object o = new JSONParser().parse( resp.trim() ); // Parsed response
            JSONObject first = (JSONObject) ( (JSONArray) o ).get( 0 ); // First message
            return new String[]{ String.valueOf( first.get( "type" ) ), String.valueOf( first.get( "info" ) ) };
        } catch ( Exception e ) {
            return new String[]{ null, resp };
        }
    }

    /**
     * @description Parses a long, returning 0 on failure
     * @param o Value
     * @return long
     */
    private static long parseLong( Object o ) {
        try {
            return Long.parseLong( String.valueOf( o ) );
        } catch ( Exception e ) {
            return 0;
        }
    }
}
