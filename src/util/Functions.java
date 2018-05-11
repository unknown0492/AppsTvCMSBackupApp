package util;



import static appstvcmsbackup.AppsTvCMSBackup.HOTEL_ID;
import static appstvcmsbackup.AppsTvCMSBackup.HOTEL_NAME;
import static appstvcmsbackup.AppsTvCMSBackup.SUPPORT_EMAIL;
import static appstvcmsbackup.AppsTvCMSBackup.URL_WEBSERVICE;
import appstvcmsbackup.UtilDate;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JOptionPane;


import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;


public class Functions {

    private final static String ACCESS_KEY = "348a448a51d1e0f0f5eee42337d12adc";
    
    // ----- Making GET/POST Request Starts Here
    public static String makeRequestForData( String url, List<BasicNameValuePair> params ){
        /*StringBuffer response = null;
        URL obj = null;
        HttpURLConnection con = null;*/
        CloseableHttpClient httpclient = HttpClients.createDefault();
        HttpPost httppost = new HttpPost( url );
        HttpResponse response = null;
        HttpEntity entity = null;
        StringBuffer resp = null;


        try {
            httppost.setEntity( new UrlEncodedFormEntity( params, "UTF-8" ) );
            response = httpclient.execute( httppost );
            entity = response.getEntity();
        }
        catch ( Exception e ) {
            System.out.println( "Failed to connect to the URL !" );
        }

        if ( entity != null ) {	
            try{
                BufferedReader in = new BufferedReader( new InputStreamReader( entity.getContent() ) );
                String inputLine;
                resp = new StringBuffer();

                while ( ( inputLine = in.readLine() ) != null ) {
                    resp.append( inputLine );
                }
                in.close();
            }
            catch( Exception e ){
                e.printStackTrace();
                return null;
            }
        }
        else
            return null;


        return resp.toString();
    }
    // ----- Making GET/POST Request Ends Here
    
    
    public static void sendEmailToSupport(){
        
        System.out.println( "Sending Email to Support !" );
        
        List<BasicNameValuePair> params = new ArrayList<BasicNameValuePair>();
        //params.add( new BasicNameValuePair( "what_do_you_want", "send_interface_failure_email" ) );
        params.add( new BasicNameValuePair( "key", "348a448a51d1e0f0f5eee42337d12adc" ) );
        params.add( new BasicNameValuePair( "hotel_name", HOTEL_NAME ) );
        params.add( new BasicNameValuePair( "support_email", SUPPORT_EMAIL ) );
        
        makeRequestForData( URL_WEBSERVICE, params );
        
    }
    
    public static void updateDatabaseBackupLogsIntoDatabase( boolean start_or_end ){
        
        System.out.println( "Updating Logs...Please wait" );
        
        List<BasicNameValuePair> params = new ArrayList<BasicNameValuePair>();
        params.add( new BasicNameValuePair( "what_do_you_want", "store_logs" ) );
        params.add( new BasicNameValuePair( "key", ACCESS_KEY ) );
        params.add( new BasicNameValuePair( "date", UtilDate.getTodaysFullDate() ) );
        params.add( new BasicNameValuePair( "backup_logs_for", "db" ) );
        params.add( new BasicNameValuePair( "is_start", (start_or_end)?"yes":"no" ) );
        
        if( start_or_end )
            params.add( new BasicNameValuePair( "db_backup_start_ts", String.valueOf( System.currentTimeMillis() ) ) );
        else
            params.add( new BasicNameValuePair( "db_backup_finish_ts", String.valueOf( System.currentTimeMillis() ) ) );
        
        params.add( new BasicNameValuePair( "hotel_id", HOTEL_ID ) );
        
        System.out.println( makeRequestForData( URL_WEBSERVICE, params ) );
    }
    
    public static void updateAppstvBackupLogsIntoDatabase( boolean start_or_end ){
        
        System.out.println( "Updating Logs...Please wait" );
        
        List<BasicNameValuePair> params = new ArrayList<BasicNameValuePair>();
        params.add( new BasicNameValuePair( "what_do_you_want", "store_logs" ) );
        params.add( new BasicNameValuePair( "key", ACCESS_KEY ) );
        params.add( new BasicNameValuePair( "date", UtilDate.getTodaysFullDate() ) );
        params.add( new BasicNameValuePair( "backup_logs_for", "appstv" ) );
        params.add( new BasicNameValuePair( "is_start", (start_or_end)?"yes":"no" ) );
        
        if( start_or_end )
            params.add( new BasicNameValuePair( "appstv_backup_start_ts", String.valueOf( System.currentTimeMillis() ) ) );
        else
            params.add( new BasicNameValuePair( "appstv_backup_finish_ts", String.valueOf( System.currentTimeMillis() ) ) );
        
        params.add( new BasicNameValuePair( "hotel_id", HOTEL_ID ) );
        
        System.out.println( makeRequestForData( URL_WEBSERVICE, params ) );
    }
    
    
    public static void alert( String title, String message ){
        JOptionPane.showMessageDialog( null, message, title, JOptionPane.INFORMATION_MESSAGE );
    }
    
    
}
