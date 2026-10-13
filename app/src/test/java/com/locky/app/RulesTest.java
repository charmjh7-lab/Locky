package com.locky.app;
import org.junit.Test;
import java.time.*;
import java.util.*;
import static org.junit.Assert.*;
public class RulesTest {
    @Test public void focusAndFreeBoundaries(){assertFalse(Rules.managed(539,540,1260));assertTrue(Rules.managed(540,540,1260));assertTrue(Rules.managed(1259,540,1260));assertFalse(Rules.managed(1260,540,1260));}
    @Test public void overnightWindow(){assertTrue(Rules.managed(1380,1320,420));assertTrue(Rules.managed(419,1320,420));assertFalse(Rules.managed(420,1320,420));assertFalse(Rules.managed(1200,1320,420));}
    @Test public void limitsAndRemainder(){assertEquals(120,Rules.dailyMinutes(150));assertEquals(5,Rules.sessionMinutes(1));assertEquals(10,Rules.sessionMinutes(99));assertEquals(600000,Rules.grant(3600000,10));assertEquals(90000,Rules.grant(90000,5));assertEquals(0,Rules.grant(0,10));}
    @Test public void splitAcrossFreeAndMidnight(){ZoneId z=ZoneId.of("Asia/Seoul");long a=ZonedDateTime.of(2026,10,13,20,55,0,0,z).toInstant().toEpochMilli(),b=ZonedDateTime.of(2026,10,14,9,5,0,0,z).toInstant().toEpochMilli();assertEquals(600000,Rules.managedMillis(a,b,540,1260,z));assertEquals(ZonedDateTime.of(2026,10,14,0,0,0,0,z).toInstant().toEpochMilli(),Rules.nextDay(a,z));}
    @Test public void deterministicShufflePreservesPurposes(){List<String> p=Arrays.asList("contact","lookup","task","rest","aimless");assertEquals(Rules.shuffle(p,"2026-10-13","*"),Rules.shuffle(p,"2026-10-13","*"));assertEquals(new HashSet<>(p),new HashSet<>(Rules.shuffle(p,"2026-10-13","*")));assertNotEquals(Rules.shuffle(p,"2026-10-13","*"),Rules.shuffle(p,"2026-10-14","*"));}
    @Test public void exhaustedOvernightAppStaysLockedUntilMorning(){ZoneId z=ZoneId.of("Asia/Seoul");long exhausted=ZonedDateTime.of(2026,10,13,23,30,0,0,z).toInstant().toEpochMilli(),free=ZonedDateTime.of(2026,10,14,7,0,0,0,z).toInstant().toEpochMilli();assertEquals(free,Rules.nextBoundary(exhausted,1320,420,z));assertTrue(Rules.nextDay(exhausted,z)<free);}
    @Test public void daylightSavingsDayIsNotAssumed24Hours(){ZoneId z=ZoneId.of("America/New_York");long t=ZonedDateTime.of(2026,3,8,0,0,0,0,z).toInstant().toEpochMilli();assertEquals(23*3600000L,Rules.nextDay(t,z)-Rules.dayStart(t,z));}
}
