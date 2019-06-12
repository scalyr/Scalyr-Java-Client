package com.scalyr.api.tests;

import com.scalyr.api.TuningConstants;
import com.scalyr.api.knobs.Knob;
import com.scalyr.api.logs.EventAttributes;
import com.scalyr.api.logs.Events;
import com.scalyr.api.logs.LogService;


import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Tests for Gzip compression.
 */
public class GzipTest extends LogsTestBase {
  @Test public void testGzipOnJavaNetHttpClient() {
    LogService testService = new LogService("0rXw/66WWhDPKouk0rZDkePU7D6bWpoQTOGYFxH/K1yc-");
    testService = testService.setServerAddress("http://localhost:8080");

    Events._reset("testGzipSession", testService, 999999, false, true);

    // Enable Gzip if not enabled by default
    if (!Events.ENABLE_GZIP_BY_DEFAULT) Events.enableGzip();

    Events.info(new EventAttributes("tag", "testWithGzipJava"));

    Events.flush();

  }

  @Test public void testGzipOnApacheHttpClient() {
    LogService testService = new LogService("0rXw/66WWhDPKouk0rZDkePU7D6bWpoQTOGYFxH/K1yc-");
    testService = testService.setServerAddress("http://localhost:8080");

    Events._reset("testGzipSession", testService, 999999, false, true);

    // Enable Gzip if not enabled by default
    if (!Events.ENABLE_GZIP_BY_DEFAULT) Events.enableGzip();

    // Turn on usage of ApacheHTTPClient
    TuningConstants.useApacheHttpClientForEventUploader = new Knob.Boolean("foo", true);

    Events.info(new EventAttributes("tag", "testWithGzipApache"));

    Events.flush();

    TuningConstants.useApacheHttpClientForEventUploader = null; // Set it back to original value of null

  }
}
