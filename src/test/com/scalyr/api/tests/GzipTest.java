package com.scalyr.api.tests;

import com.scalyr.api.TuningConstants;
import com.scalyr.api.knobs.ConfigurationFile;
import com.scalyr.api.knobs.Knob;
import com.scalyr.api.logs.EventAttributes;
import com.scalyr.api.logs.Events;
import com.scalyr.api.logs.LogService;


import org.junit.After;
import org.junit.Ignore;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Tests for Gzip compression. These are marked as @Ignore b/c they are dependent on a local server instance
 * or a remote Scalyr environment. You must manually check that the test logs are received and decompressed
 * correctly at the destination.
 */
public class GzipTest extends LogsTestBase {
  @Test @Ignore
  public void testGzipOnJavaNetHttpClient() {
    // Put appropriate log write token into LogService()
    LogService testService = new LogService("0rXw/66WWhDPKouk0rZDkePU7D6bWpoQTOGYFxH/K1yc-");
    // Either put localhost here, or a particular Scalyr environment.
    testService = testService.setServerAddress("http://localhost:8080");

    Events._reset("testGzipSession", testService, 999999, false, true);

    // Enable Gzip if not enabled by default
    if (!Events.ENABLE_GZIP_BY_DEFAULT) Events.enableGzip();

    Events.info(new EventAttributes("tag", "testWithGzipJava", "foo1", "bla1", "foo2", "bla2"));

    Events.flush();

  }

  @Test @Ignore
  public void testGzipOnApacheHttpClient() {
    Knob.setDefaultFiles(new ConfigurationFile[0]);

    // Put appropriate log write token into LogService()
    LogService testService = new LogService("0rXw/66WWhDPKouk0rZDkePU7D6bWpoQTOGYFxH/K1yc-");
    // Either put localhost here, or a particular Scalyr environment.
    testService = testService.setServerAddress("http://localhost:8080");

    Events._reset("testGzipSession", testService, 999999, false, true);

    // Enable Gzip if not enabled by default
    if (!Events.ENABLE_GZIP_BY_DEFAULT) Events.enableGzip();

    // Turn on usage of ApacheHTTPClient
    TuningConstants.useApacheHttpClientForEventUploader = new Knob.Boolean("foo", true);

    Events.info(new EventAttributes("tag", "testWithGzipApache", "foo1", "bla1", "foo2", "bla2"));

    Events.flush();

    TuningConstants.useApacheHttpClientForEventUploader = null; // Set it back to original value of null

  }
}
