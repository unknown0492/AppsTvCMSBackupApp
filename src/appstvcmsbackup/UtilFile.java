/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package appstvcmsbackup;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

/**
 *
 * @author Sohail
 */
public class UtilFile {
    
    public static String readData( File file ){
        FileInputStream fis = null;
        BufferedReader reader = null;
        StringBuilder data;

        try{
            fis = new FileInputStream( file );
            reader = new BufferedReader( new InputStreamReader( fis ) );
            data = new StringBuilder();
            String line = null;

            while ( ( line = reader.readLine() ) != null ){
                data.append( line ).append( "\n" );
            }
            reader.close();
            fis.close();
        }
        catch ( Exception e ) {
            e.printStackTrace();
            return null;
        }
        return data.toString();
    }
    
}
