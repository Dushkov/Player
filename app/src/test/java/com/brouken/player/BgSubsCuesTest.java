package com.brouken.player;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class BgSubsCuesTest {

    private static final String SRT = "1\r\n00:00:01,000 --> 00:00:02,000\r\n<i>Здравей</i>\r\n\r\n"
            + "2\r\n00:00:03,000 --> 00:00:05,500\r\n{\\an8}Два\r\nреда\r\n\r\n"
            + "3\r\n00:00:04,000 --> 00:00:04,500\r\n<font color=\"#ffff00\">Отгоре</font>\r\n";

    @Test
    public void parsesCuesStrippingTags() {
        BgSubsCues cues = BgSubsCues.parse(SRT);
        assertEquals(3, cues.size());
        assertEquals(Collections.singletonList("Здравей"), cues.textsAt(1500, 0));
        assertEquals(Collections.singletonList("Два\nреда"), cues.textsAt(3500, 0));
    }

    @Test
    public void overlappingCuesAreAllShownInOrder() {
        assertEquals(Arrays.asList("Два\nреда", "Отгоре"), BgSubsCues.parse(SRT).textsAt(4200, 0));
    }

    @Test
    public void endIsExclusive() {
        BgSubsCues cues = BgSubsCues.parse(SRT);
        assertEquals(Collections.emptyList(), cues.textsAt(2000, 0));
        assertEquals(Collections.emptyList(), cues.textsAt(999, 0));
    }

    @Test
    public void positiveOffsetShowsLater() {
        BgSubsCues cues = BgSubsCues.parse(SRT);
        assertEquals(Collections.emptyList(), cues.textsAt(1200, 500));
        assertEquals(Collections.singletonList("Здравей"), cues.textsAt(1600, 500));
        assertEquals(Collections.singletonList("Здравей"), cues.textsAt(2400, 500));
    }

    @Test
    public void negativeOffsetShowsEarlier() {
        BgSubsCues cues = BgSubsCues.parse(SRT);
        assertEquals(Collections.singletonList("Здравей"), cues.textsAt(700, -500));
        assertEquals(Collections.emptyList(), cues.textsAt(1600, -500));
    }

    @Test
    public void emptyOrBrokenInputGivesNoCues() {
        assertEquals(0, BgSubsCues.parse("").size());
        assertEquals(0, BgSubsCues.parse("просто текст").size());
    }

    @Test
    public void offsetWordsSayTheDirection() {
        assertEquals("1,2 s по-късно", BgSubsCues.offsetWords(1200));
        assertEquals("0,5 s по-рано", BgSubsCues.offsetWords(-500));
        assertEquals("без отместване", BgSubsCues.offsetWords(0));
        assertEquals("12,0 s по-късно", BgSubsCues.offsetWords(12000));
    }

    @Test
    public void syncStatusIsAFullSentence() {
        assertEquals("Субтитрите идват 1,2 s по-късно", BgSubsCues.syncStatus(1200));
        assertEquals("Субтитрите идват 0,1 s по-рано", BgSubsCues.syncStatus(-100));
        assertEquals("Субтитрите са без отместване", BgSubsCues.syncStatus(0));
    }

    @Test
    public void nextCueIsTheFirstNotYetShownWithTheCurrentOffset() {
        BgSubsCues cues = BgSubsCues.parse(SRT);  // cues start at 1000, 3000, 4000
        assertEquals(0, cues.nextCue(500, 0));
        assertEquals(1, cues.nextCue(1500, 0));
        assertEquals(1, cues.nextCue(2500, 1000));   // with +1 s the first cue is due at 2000, already past
        assertEquals(0, cues.nextCue(2500, 2000));   // with +2 s it is due at 3000, still ahead
        assertEquals(-1, cues.nextCue(9000, 0));
    }

    @Test
    public void nextCueAfterSkipsToTheFollowingOne() {
        BgSubsCues cues = BgSubsCues.parse(SRT);
        assertEquals(2, cues.nextCueAfter(1));
        assertEquals(-1, cues.nextCueAfter(2));
    }

    @Test
    public void tapGivesTheOffsetFromWhenTheLineWasHeard() {
        BgSubsCues cues = BgSubsCues.parse(SRT);
        assertEquals("Два\nреда", cues.text(1));
        assertEquals(1500, cues.offsetForTap(1, 4500));   // line written for 3,0 s heard at 4,5 s
        assertEquals(-800, cues.offsetForTap(1, 2200));
    }
}
