package com.locky.app;

import java.time.*;
import java.util.*;

/** Clock-independent policy, also used by the service and daily reports. */
final class Rules {
    static boolean managed(int minute,int start,int end) {
        if(start==end)return true;
        return start<end?minute>=start&&minute<end:minute>=start||minute<end;
    }
    static int minute(long time,ZoneId zone) { ZonedDateTime z=Instant.ofEpochMilli(time).atZone(zone);return z.getHour()*60+z.getMinute(); }
    static long dayStart(long time,ZoneId zone) { return Instant.ofEpochMilli(time).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli(); }
    static long nextDay(long time,ZoneId zone) { return Instant.ofEpochMilli(time).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(); }
    static long nextBoundary(long now,int start,int end,ZoneId zone) {
        LocalDate date=Instant.ofEpochMilli(now).atZone(zone).toLocalDate();long best=Long.MAX_VALUE;
        for(int d=0;d<3;d++)for(int m:new int[]{start,end}) {
            long t=date.plusDays(d).atTime(m/60,m%60).atZone(zone).toInstant().toEpochMilli();if(t>now)best=Math.min(best,t);
        }
        return best;
    }
    static long managedMillis(long from,long to,int start,int end,ZoneId zone) {
        long total=0,t=from;while(t<to) {long next=Math.min(to,nextBoundary(t,start,end,zone));if(managed(minute(t,zone),start,end))total+=next-t;t=next;}return total;
    }
    static int dailyMinutes(int value) { return Math.max(5,Math.min(120,value)); }
    static int sessionMinutes(int value) { return Math.max(5,Math.min(10,value)); }
    static long grant(long dailyRemaining,int minutes) { return Math.max(0,Math.min(dailyRemaining,sessionMinutes(minutes)*60000L)); }
    static long dailyRank(String date,String scope,String id){long h=0xcbf29ce484222325L;for(char c:(date+"|"+scope+"|"+id).toCharArray()){h^=c;h*=0x100000001b3L;}h^=h>>>33;h*=0xff51afd7ed558ccdL;h^=h>>>33;return h;}
    static List<String> shuffle(List<String> values,String date,String scope) {
        // A per-ID rank preserves all existing relative positions when a preset is edited,
        // added or removed. Date and scope independently seed the daily permutation.
        List<String> out=new ArrayList<>(values);
        out.sort(Comparator.<String>comparingLong(id->dailyRank(date,scope,id)).thenComparing(id->id));return out;
    }
}
