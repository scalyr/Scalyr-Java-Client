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

package com.scalyr.api;

import java.util.Date;

import com.scalyr.api.internal.Logging;
import com.scalyr.api.internal.SimpleRateLimiter;
import com.scalyr.api.logs.Severity;

/**
 * A LogHook receives all messages logged by the Scalyr client library.
 */
public abstract class LogHook {
  /**
   * Log a message regarding the internal functioning of the Scalyr client library.
   *
   * @param severity Severity / importance of this message.
   * @param tag An invariant identifier for this message.
   * @param message Human-readable message.
   * @param ex Exception associated with this message, or null.
   */
  public abstract void log(Severity severity, String tag, String message, Throwable ex);

  /**
   * Log a message regarding the internal functioning of the Scalyr client library. Messages with no
   * specific sender may be sent to the other overload of log().
   *
   * @param sender Internal object which generated this log message, or null. For internal use only.
   * @param severity Severity / importance of this message.
   * @param tag An invariant identifier for this message.
   * @param message Human-readable message.
   * @param ex Exception associated with this message, or null.
   */
  public void log(Object sender, Severity severity, String tag, String message, Throwable ex) {
    log(severity, tag, message, ex);
  }

  public static class ThresholdLogger extends LogHook {
    /**
     * Messages below this severity are not logged.
     */
    private final Severity minSeverity;

    /**
     * Used to throttle the number of lines we output to stdout.
     */
    private final SimpleRateLimiter rateLimiter;

    /**
     * Separate throttler used for tagKnobFileInvalid. This message is sometimes
     * generated in large bursts; we throttle it separately to avoid poisioning
     * other log output.
     */
    private final SimpleRateLimiter knobFileInvalidRateLimiter;

    /**
     * Used to throttle warning messages indicating that rateLimiter has kicked in.
     */
    private final SimpleRateLimiter overflowLimiter = new SimpleRateLimiter(
        1.0 / TuningConstants.DIAGNOSTIC_OVERFLOW_WARNING_INTERVAL_SECS, 1.0);

    /**
     * We add this prefix to all log messages, immediately after the date.
     */
    private final String prefix;

    public ThresholdLogger(Severity minSeverity) {
      this(minSeverity,
          new SimpleRateLimiter(TuningConstants.MAX_DIAGNOSTIC_MESSAGES_PER_SECOND,
              TuningConstants.MAX_DIAGNOSTIC_MESSAGE_BURST),
          new SimpleRateLimiter(TuningConstants.MAX_KNOBFILEINVALID_MESSAGES_PER_SECOND,
              TuningConstants.MAX_KNOBFILEINVALID_MESSAGE_BURST));
    }

    public ThresholdLogger(Severity minSeverity, SimpleRateLimiter rateLimiter, SimpleRateLimiter knobFileInvalidRateLimiter) {
      this(minSeverity, rateLimiter, knobFileInvalidRateLimiter, ": ");
    }

    public ThresholdLogger(Severity minSeverity, SimpleRateLimiter rateLimiter, SimpleRateLimiter knobFileInvalidRateLimiter,
        String prefix) {
      this.minSeverity = minSeverity;
      this.rateLimiter = rateLimiter;
      this.knobFileInvalidRateLimiter = knobFileInvalidRateLimiter;
      this.prefix = prefix;
    }

    @Override public void log(Severity severity, String tag, String message, Throwable ex) {
      // Log all messages at or above minSeverity to stdout.
      if (severity.ordinal() >= minSeverity.ordinal()) {
        SimpleRateLimiter limiter = (tag.equals(Logging.tagKnobFileInvalid)) ? knobFileInvalidRateLimiter : rateLimiter;
        if (limiter.consume(1.0)) {
          System.out.println(new Date() + prefix + tag + " (" + message + ")");
          if (ex != null)
            ex.printStackTrace(System.out);
        } else {
          if (overflowLimiter.consume(1.0)) {
            System.out.println(new Date() + ": WARNING -- diagnostic messages have exceeded "
                + limiter.fillRatePerMs * 1000 + " messages/second; temporarily throttling output");
          }
        }
      }
    }
  }

  /**
   * Specify the LogHook object to process internal messages logged by the Scalyr client.
   * Replaces any previous hook.
   */
  public static void setHook(LogHook value) {
    Logging.setHook(value);
  }
}
