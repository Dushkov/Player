package com.brouken.player;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

public class BgSubsSmokeTest {
    @Test
    public void orgJsonWorksInUnitTests() throws Exception {
        assertEquals(1, new JSONObject("{\"a\":1}").getInt("a"));
    }
}
