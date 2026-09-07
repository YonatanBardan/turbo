package com.turbo.backend_analytics.util;

public class TimeUtil {

    // Returns: Formatted mm:ss string ("01:12")
    public static String formatTime(double totalSec){
        int secInt = (int) totalSec;
        int minutes = secInt / 60;
        int seconds = secInt % 60;

        return String.format("%02d:%02d", minutes, seconds);
    }
}
