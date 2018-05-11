/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package appstvcmsbackup;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.Calendar;
import org.apache.commons.io.FileUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import static util.Functions.updateAppstvBackupLogsIntoDatabase;
import static util.Functions.updateDatabaseBackupLogsIntoDatabase;

/**
 *
 * @author Sohail
 */
public class AppsTvCMSBackup {

    public static String XAMPP_BACKUP_DIR_PATH = "";
    public static String TO_BACKUP_DIR_PATH = "";
    public static String FROM_BACKUP_DIR_PATH = "";
    public static String DB_BACKUP_DIR_PATH = "";
    public static int BACKUP_FREQUENCY = 5;
    public static String HOTEL_NAME = "";
    public static String SUPPORT_EMAIL = "";
    public static String URL_WEBSERVICE = "";
    public static String DB_NAME = "";
    public static int DB_BACKUPS_COUNT = 5;
    public static int APPSTV_BACKUPS_COUNT = 3;
    public static String DB_HAS_PASSWORD = "no";
    public static String HOTEL_ID = "";
    
    
    
    public static void main( String[] args ) {
        
        init();
        
        // Take backup of the Apps TV files, every 10 days, that is , 1st, 10th, 20th and 30th of every month
        // Maintain last 4 backups only
        System.out.println( "Checking if the backup media exist..." );
        File dest = new File( TO_BACKUP_DIR_PATH );
        if( dest.exists() ){
            System.out.println( "Backup media exist..." );
        }
        else{
            System.out.println( "Backup media does not exist. Backup is exiting !" );
            return;
        }
        
        // Database backup everyday
        doDatabaseBackup();

        // CMS Backup needs to be done every x days (config file)
        int date_today = Integer.parseInt( UtilDate.getTodaysDate() );
        if( date_today%BACKUP_FREQUENCY != 0 ){
            return;
        }
        
        
        doAppsTvBackup();
        
        
    }
    
    private static void init(){
    
        readConfigFile();
        
        checkPreRequisites();
        
    }
    
    private static void readConfigFile(){
        
        String config_data = UtilFile.readData( new File( "config" ) );
        JSONParser parser = new JSONParser();
        
        JSONObject jsonObject = null;
        
        try{
            jsonObject = (JSONObject) parser.parse( config_data );
            
            XAMPP_BACKUP_DIR_PATH = (String) jsonObject.get( "xampp_backup_dir_path" );
            TO_BACKUP_DIR_PATH = (String) jsonObject.get( "to_backup_dir_path" );
            FROM_BACKUP_DIR_PATH = (String) jsonObject.get( "from_backup_dir_path" );
            DB_BACKUP_DIR_PATH = (String) jsonObject.get( "db_backup_dir_path" );
            BACKUP_FREQUENCY = Integer.parseInt( ((String) jsonObject.get( "backup_frequency" ) ) );
            URL_WEBSERVICE = (String) jsonObject.get( "url_webservice" );
            HOTEL_NAME = (String) jsonObject.get( "hotel_name" );
            SUPPORT_EMAIL = (String) jsonObject.get( "support_email" );
            DB_NAME = (String) jsonObject.get( "db_name" );
            DB_BACKUPS_COUNT = Integer.parseInt( ((String) jsonObject.get( "db_backups_count" ) ) );
            APPSTV_BACKUPS_COUNT = Integer.parseInt( ((String) jsonObject.get( "appstv_backups_count" ) ) );
            DB_HAS_PASSWORD = (String) jsonObject.get( "db_has_password" );
            HOTEL_ID = (String) jsonObject.get( "hotel_id" );
            
        }
        catch( Exception e ){
            e.printStackTrace();
        }
        
    }
    
    private static void checkPreRequisites(){
    
        // If one day, the thumb drive gets corrupt and someone puts in an empty thumbdrive, then create the folders automatically
        File file1 = new File( TO_BACKUP_DIR_PATH );
        if( ! file1.exists() ){
            //System.out.println( TO_BACKUP_DIR_PATH + " does not exist!" );
            file1.mkdirs();
        }
        
        File file2 = new File( DB_BACKUP_DIR_PATH );
        if( ! file2.exists() )
            file2.mkdirs();
        
        /*
        File file3 = new File( XAMPP_BACKUP_DIR_PATH );
        if( ! file3.exists() ){
            for( int i = 1 ; i <=5 ; i++ ){
                for( int j = 1 ; j <=50 ; j++ ){
                    System.out.print( "*" );
                }
                System.out.println();
            }
            System.out.println( "XAMPP directory is missing in the Thumbdrive !" );
            for( int i = 1 ; i <=5 ; i++ ){
                for( int j = 1 ; j <=50 ; j++ ){
                    System.out.print( "*" );
                }
                System.out.println();
            }
        }
        */  
        
        
    }

    private static void doAppsTvBackup(){
        
        
        
        String today = UtilDate.getTodaysFullDate();
        // Create a directory with this name inside appstv_backups folder
        File appstv_backup_dir = new File( TO_BACKUP_DIR_PATH + File.separator + today );
        if( !appstv_backup_dir.exists() ){
            appstv_backup_dir.mkdirs();
        }
        if( appstv_backup_dir.exists() ){
            
            //appstv_backup_dir.mkdirs();
            
            System.out.println( "Created directory : " + today );
            
            File srcDir = new File( FROM_BACKUP_DIR_PATH );
            File destDir = new File( TO_BACKUP_DIR_PATH + File.separator + today );
            try{
                
                File to_backup_dir = new File( TO_BACKUP_DIR_PATH );
                File[] files = to_backup_dir.listFiles();
                System.out.println( "Length : " + files.length );
                //return;
                if( files.length > APPSTV_BACKUPS_COUNT ){
                    // Find the oldest backup and delete it
                    String f1 = files[ 0 ].getName();
                    String f2 = files[ 1 ].getName();
                    String f3 = files[ 2 ].getName();
                    
                    Calendar c1 = UtilDate.getCalFromDate( f1 );
                    Calendar c2 = UtilDate.getCalFromDate( f2 );
                    Calendar c3 = UtilDate.getCalFromDate( f3 );
                    
                    if( c1.before( c2 ) && c1.before( c3 ) ){
                        FileUtils.deleteDirectory( new File( TO_BACKUP_DIR_PATH + File.separator + f1 ) );
                    }
                    else if( c2.before( c1 ) && c2.before( c3 ) ){
                        FileUtils.deleteDirectory( new File( TO_BACKUP_DIR_PATH + File.separator + f2 ) );
                    }
                    else if( c3.before( c1 ) && c3.before( c2 ) ){
                        FileUtils.deleteDirectory( new File( TO_BACKUP_DIR_PATH + File.separator + f3 ) );
                    }
                    System.out.println( "Deleting old backup...Please do not close this window !!!\n" );
                }
                
                System.out.println( "Copying files...This may take some time. Please do not close this window !!!\n" );
                updateAppstvBackupLogsIntoDatabase( true );
                FileUtils.copyDirectory( srcDir, destDir );
                updateAppstvBackupLogsIntoDatabase( false );
                /*
                String b1 = ((String)jsonObject.get( "backup1" )).trim();
                String b2 = ((String)jsonObject.get( "backup2" )).trim();
                String b3 = ((String)jsonObject.get( "backup3" )).trim();
                
                if( b1.equals( "" ) ){
                    regenerateBackupJSON( "backup1", today );
                }
                else if( b2.equals( "" ) ){
                    regenerateBackupJSON( "backup2", today );
                }
                else if( b3.equals( "" ) ){
                    regenerateBackupJSON( "backup3", today );
                }
                else{
                    // when all three backups are done, then delete 
                }
                */
                
                
                
                // FileUtils.deleteDirectory();
                
                System.out.println( "AppsTv Backup successful !\n" );
            }
            catch( Exception e ){
                e.printStackTrace();
            }
        }
        
    }
    
    private static void doDatabaseBackup(){
    
        String today = UtilDate.getTodaysFullDate();
        try{

            File to_backup_dir = new File( DB_BACKUP_DIR_PATH );
            File[] files = to_backup_dir.listFiles();
            System.out.println( "Length : " + files.length );
            if( files.length > (DB_BACKUPS_COUNT - 1) ){
                // Find the oldest backup and delete it
                String f1 = files[ 0 ].getName();
                String f2 = files[ 1 ].getName();
                String f3 = files[ 2 ].getName();
                
                Calendar c1 = UtilDate.getCalFromDate( f1.substring( 0, f1.lastIndexOf( "." ) - 1 ) );
                Calendar c2 = UtilDate.getCalFromDate( f2.substring( 0, f2.lastIndexOf( "." ) - 1 ) );
                Calendar c3 = UtilDate.getCalFromDate( f3.substring( 0, f3.lastIndexOf( "." ) - 1 ) );

                if( c1.before( c2 ) && c1.before( c3 ) ){
                    files[ 0 ].delete();
                }
                else if( c2.before( c1 ) && c2.before( c3 ) ){
                    files[ 1 ].delete();
                }
                else if( c3.before( c1 ) && c3.before( c2 ) ){
                    files[ 2 ].delete();
                }
                System.out.println( "Deleting old SQL backup...Please do not close this window !!!\n" );
            }
            
            File file_name = new File( to_backup_dir + File.separator + today + ".sql" );

            System.out.println( "Exporting SQL...This may take some time. Please do not close this window !!!\n" );
            
            updateDatabaseBackupLogsIntoDatabase( true );
            
            if( DB_HAS_PASSWORD.equals( "yes" ) )
                executeCommand( new String[]{ "cmd.exe", "/c", "C:\\xampp\\mysql\\bin\\mysqldump --user=root --password=ess530108 "+DB_NAME+" > "+file_name.getAbsolutePath() } );
            else
                executeCommand( new String[]{ "cmd.exe", "/c", "C:\\xampp\\mysql\\bin\\mysqldump --user=root "+DB_NAME+" > "+file_name.getAbsolutePath() } );
            //System.out.println( "op : "+op );
            //System.out.println( "C:\\xampp\\mysql\\bin\\mysqldump.exe --user=root "+DB_NAME+" > "+file_name.getAbsolutePath() );
            
            updateDatabaseBackupLogsIntoDatabase( false );
            System.out.println( "Database Backup successful !\n" );
        }
        catch( Exception e ){
            e.printStackTrace();
        }
    
    }
    
    
    private static void regenerateBackupJSON( String backup_name_key, String backup_name ){
        
        String backup_data = UtilFile.readData( new File( "backups" ) );
        JSONParser parser = new JSONParser();
        
        JSONObject jsonObject = null;
        JSONArray jsonArray = null;
        
        JSONArray jsonArrayNew = new JSONArray();
        JSONObject jsonObjectNew = null;
        try{
        
            jsonArray   = (JSONArray) parser.parse( backup_data );
            jsonObject  = (JSONObject) jsonArray.get( 0 );
            
            for( int i = 0 ; i < jsonArray.size() ; i++ ){
                if( i == 1 ){
                
                    jsonObjectNew.put( "backup1", jsonObject.get( "backup1" ) );
                    jsonObjectNew.put( "backup2", jsonObject.get( "backup2" ) );
                    jsonObjectNew.put( "backup3", jsonObject.get( "backup3" ) );
                    
                }else{
                    
                    if( backup_name_key.equals( "backup1" ) ) jsonObjectNew.put( "backup1", backup_name );
                    else jsonObjectNew.put( "backup1", jsonObject.get( "backup1" ) );

                    if( backup_name_key.equals( "backup2" ) ) jsonObjectNew.put( "backup2", backup_name );
                    else jsonObjectNew.put( "backup2", jsonObject.get( "backup2" ) );

                    if( backup_name_key.equals( "backup3" ) ) jsonObjectNew.put( "backup3", backup_name );
                    else jsonObjectNew.put( "backup3", jsonObject.get( "backup3" ) );
                    
                }
                
                jsonArrayNew.add( i, jsonObjectNew );
            }
            
        }
        catch( Exception e ){
            e.printStackTrace();
        }
    }

    public static String executeCommand(String[] command) {

        StringBuffer output = new StringBuffer();

        Process p;
        try {
                p = Runtime.getRuntime().exec(command);
                p.waitFor();
                BufferedReader reader = 
                   new BufferedReader(new InputStreamReader(p.getInputStream()));

                String line = "";	
                while ((line = reader.readLine())!= null) {
                        output.append(line + "\n");
                }

        } catch (Exception e) {
                e.printStackTrace();
        }

        return output.toString();

    }
}