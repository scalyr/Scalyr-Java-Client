/*
 * Scalyr client library
 * Copyright 2012 Scalyr, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.scalyr.api.internal;

import java.util.UUID;

import com.scalyr.api.LogHook;
import com.scalyr.api.logs.EventAttributes;
import com.scalyr.api.logs.EventUploader;
import com.scalyr.api.logs.LogService;
import com.scalyr.api.logs.Severity;

/**
 * WARNING: this class, and all classes in the .internal package, should not be
 * used by client code. (This means you.) We reserve the right to make incompatible
 * changes to the .internal package at any time.
 */
public class Logging {
  /**
   * Hook which receives all internal messages logged by the Scalyr client.
   */
  private static volatile LogHook hook = new LogHook.ThresholdLogger(Severity.info);

  /**
   * An uploader used for "meta-reporting", i.e. logging statistics about the internal
   * behavior of the Scalyr client library itself to a Scalyr Logs server. Null unless
   * enableMetaMonitoring() has been called.
   */
  private static volatile EventUploader metaReportingUploader;

  /**
   * Specify the LogHook object to process internal messages logged by the Scalyr client.
   * Replaces any previous hook.
   */
  public static void setHook(LogHook value) {
    hook = value;
  }

  /**
   * Enable "meta-reporting" of internal Scalyr client statistics to Scalyr Logs. This should
   * be called at most once.
   *
   * @param apiToken the API token to use when sending events to Scalyr Logs.
   * @param bufferSize
   */
  public static void enableMetaMonitoring(String apiToken, int bufferSize, EventAttributes serverAttributes) {
    if (metaReportingUploader != null)
      throw new RuntimeException("enableMetaMonitoring should be called at most once");

    metaReportingUploader = new EventUploader(new LogService(apiToken),
        bufferSize, "sess_" + UUID.randomUUID(), true, serverAttributes, false, false);
  }

  public static void metaMonitorInfo(EventAttributes event) {
    if (metaReportingUploader != null)
      metaReportingUploader.rawEvent(Severity.info, event);
  }

  public static void log(Severity severity, String tag, String message) {
    log(severity, tag, message, null);
  }

  public static void log(Object sender, Severity severity, String tag, String message) {
    log(sender, severity, tag, message, null);
  }

  /**
   * Log a message regarding the internal functioning of the Scalyr client library.
   *
   * @param severity Severity / importance of this message.
   * @param tag An invariant identifier for this message; taken from one of the string
   *     constants below.
   * @param message Human-readable message.
   * @param ex Exception associated with this message, or null.
   */
  public static void log(Severity severity, String tag, String message, Throwable ex) {
    hook.log(severity, tag, message, ex);
  }

  public static void log(Object sender, Severity severity, String tag, String message, Throwable ex) {
    hook.log(sender, severity, tag, message, ex);
  }

  /**
   * Utility class used to limit log messages to a specified rate.
   */
  public static class LogLimiter {
    /**
     * Millisecond timestamp when this limiter last allowed a log event to go through,
     * or null if we never have.
     */
    private Long lastLogTimeMs = null;

    /**
     * Return true if it has been at least minIntervalMs since we last returned true.
     */
    public synchronized boolean allow(long minIntervalMs) {
      long nowMs = ScalyrUtil.currentTimeMillis();
      if (lastLogTimeMs == null || nowMs >= lastLogTimeMs + minIntervalMs) {
        lastLogTimeMs = nowMs;
        return true;
      } else {
        return false;
      }
    }
  }


  // These constants are used for the tag attribute to log().

  /**
   * Routine logging for communication with a Scalyr server.
   */
  public static final String tagServerCommunication = "server/communication";

  /**
   * Error communicating with a Scalyr server.
   */
  public static final String tagServerError = "server/error";

  /**
   * Backoff response from a Scalyr server.
   */
  public static final String tagServerBackoff = "server/error/backoff";

  /**
   * Error accessing a local configuration file.
   */
  public static final String tagLocalConfigFileError = "local/error/configFile";

  /**
   * I/O error accessing a local cache file.
   */
  public static final String tagKnobCacheIOError = "local/error/cacheFile";

  /**
   * Local cache file corrupt.
   */
  public static final String tagKnobCacheCorrupt = "local/error/cacheFile/corrupt";

  /**
   * Internal error in the client library.
   */
  public static final String tagInternalError = "local/error/internal";

  /**
   * Scalyr Logs client discarding events due to local buffer overflow.
   */
  public static final String tagLogBufferOverflow = "local/error/logBufferOverflow";

  /**
   * Knob file is not parseable as JSON.
   */
  public static final String tagKnobFileInvalid = "user/error/badKnobFile";

  /**
   * Exception escaped from Gauge.sample().
   */
  public static final String tagGaugeThrewException = "user/error/gaugeFailed";

  /**
   * Events.end() called with no matching start call.
   */
  public static final String tagMismatchedEnd = "user/error/mismatchedEnd";

  /**
   * This tag is issued periodically to report the number of bytes in the buffer
   * of events waiting to be uploaded. The message is a decimal representation of
   * the byte count.
   */
  public static final String tagBufferedEventBytes = "local/info/bufferedEventBytes";

  /**
   * This tag is issued after each attempt to upload events to the server. The
   * message is a JSON object encoding the outcome, in the form
   *
   *   {"size": nnn, "duration": nnn, "success", true|false}
   *
   * size is the size of the event buffer being uploaded, and duration is the elapsed time
   * for the upload operation (in nanoseconds).
   */
  public static final String tagEventUploadOutcome = "local/info/eventUploadOutcome";

  /**
   * This tag issued after a new EventUpload instance is created for uploading events.
   * It is a human readable message meant to give extra information, such as a link to
   * Scalyr log query to show all events from this host.
   */
  public static final String tagEventUploadSession = "local/info/eventUploadSession";

  /**
   * This tag is issued after an error is seen while creating an EventUpload instance.
   */
  public static String tagEventUploadError = "local/error/eventUploadSession";
}
