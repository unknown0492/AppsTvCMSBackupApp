/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package appstvcmsbackup;

import java.util.Calendar;

public class UtilDate {

    // Returns todays date in DD-MM-YYYY format
    public static String getTodaysFullDate(){
        Calendar cal = Calendar.getInstance();
        
        String date  = String.valueOf( cal.get( Calendar.DATE ) );
        date = (date.length()==1)?"0"+date:date;
        
        String month = String.valueOf( cal.get( Calendar.MONTH ) + 1 );
        month = (month.length()==1)?"0"+month:month;
        
        String year  = String.valueOf( cal.get( Calendar.YEAR ) );
        
        String date_string = date + "-" + month + "-" + year;
        
        return date_string;
    }
    
    
    // Returns today's date only
    public static String getTodaysDate(){
        Calendar cal = Calendar.getInstance();
        
        String date  = String.valueOf( cal.get( Calendar.DATE ) );
           
        return date;
    }
    
    public static Calendar getCalFromDate( String date ){
        
        String[] d = date.split( "-" );
        Calendar cal = Calendar.getInstance();
        cal.set( Calendar.DATE, Integer.parseInt( d[ 0 ] ) );
        cal.set( Calendar.MONTH, Integer.parseInt( d[ 1 ] ) - 1 );
        cal.set( Calendar.YEAR, Integer.parseInt( d[ 2 ] ) );
        
        return cal;
    }
}
