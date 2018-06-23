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

import com.scalyr.api.knobs.Knob;
import com.scalyr.api.logs.CounterGauge;

/**
 * Tunable parameters.
 */
public class TuningConstants {
  /**
   * Time span during which API operations may be retried (e.g. in response to a network
   * error). Once this many milliseconds have elapsed from the initial API invocation, we
   * no longer issue retries.
   */
  public static final int MAXIMUM_RETRY_PERIOD_MS = 60000;
  
  /**
   * Maximum time (in milliseconds) for opening an HTTP connection to the Scalyr server.
   * If this time is exceeded, we consider server invocation to have failed.
   */
  public static final int HTTP_CONNECT_TIMEOUT_MS = 10000;
  
  /**
   * Time (in milliseconds) from when HostedConfigurationFile completes fetching a file from
   * the server, until it issues the next request. If the fetch failed, subsequent requests
   * use a longer interval, up to MAXIMUM_FETCH_INTERVAL.
   */
  public static final int MINIMUM_FETCH_INTERVAL = 500;
  
  /**
   * Minimum interval used when the previous request was unsuccessful.
   */
  public static final int MINIMUM_FETCH_INTERVAL_AFTER_ERROR = 5000;
  
  public static final int MAXIMUM_FETCH_INTERVAL = 60000;
  
  /**
   * Maximum length of an individual attribute in a Scalyr Logs event.
   */
  public static final int MAXIMUM_EVENT_ATTRIBUTE_LENGTH = 3500;
  
  /**
   * Interval between warnings that events are being discarded due to buffer overflow.
   */
  public static final int EVENT_UPLOAD_MEMORY_WARNING_INTERVAL_MS = 10000;
  
  /**
   * Interval between sampling of registered Gauges.
   */
  public static final int GAUGE_SAMPLE_INTERVAL_MS = 30000;
  
  /**
   * Maximum payload size for a single invocation of LogService.uploadEvents.
   * This is the maximum size, in bytes, of the serialized events array.
   * 
   * Increasing this above 1MB may lead to problems where a single batch is larger
   * than the burst size the server-side rate limiter allows.
   */
  public static final int MAX_EVENT_UPLOAD_BYTES = 1024 * 1024;
  
  /**
   * Payload size which triggers invocation of LogService.uploadEvents. We wait for
   * this payload size (or EVENT_UPLOAD_TIME_THRESHOLD_MS).
   */
  public static final int EVENT_UPLOAD_BYTE_THRESHOLD = 100 * 1024;
  
  /**
   * Interval for checking whether to upload a new batch of events to the Scalyr
   * Logs service. We don't necessarily upload this often, but this specifies how
   * frequently we evaluate to see whether it's time to upload a new batch.
   */
  public static final int EVENT_UPLOAD_CHECK_INTERVAL = 1000;
  
  /**
   * Time delay which triggers invocation of LogService.uploadEvents. We wait for
   * this time delay (or EVENT_UPLOAD_BYTE_THRESHOLD). We choose a value below 5
   * seconds to prevent keepalive connections from being dropped.
   */
  public static final int EVENT_UPLOAD_TIME_THRESHOLD_MS = 4500;
  
  /**
   * Minimum time delay between event batch uploads (measured start-to-start;
   * deliberately set slightly shorter than EVENT_UPLOAD_CHECK_INTERVAL, so that
   * we can initiate an upload on each check).
   */
  public static final int MIN_EVENT_UPLOAD_SPACING_MS = 900;
  
  /**
   * Maximum time delay between event batch uploads. This comes into play when
   * the server issues backoff responses and we begin increasing our interval.
   */
  public static final int MAX_EVENT_UPLOAD_SPACING_MS = 30000;

  /**
   * If not null, then we wait at least this long after one event batch upload before
   * initiating another, regardless of any other settings. (Measures start-to-start.)
   */
  public static volatile Knob.Integer adjustableEventUploadSpacingFloorMs = null;

  /**
   * Factor by which we adjust our upload spacing after a backoff response.
   */
  public static final double UPLOAD_SPACING_FACTOR_ON_BACKOFF = 1.5;
  
  /**
   * Factor by which we adjust our upload spacing after a successful upload.
   */
  public static final double UPLOAD_SPACING_FACTOR_ON_SUCCESS = 0.6;
  
  /**
   * Percentage of EventUploader's buffer that we reserve for use by end
   * events and buffer-overflow messages. Start events and leaf events
   * will not use this space. This ensures that end events usually do not
   * need to be discarded, thus preserving the integrity of event nesting.
   */
  public static final int EVENT_BUFFER_RESERVED_PERCENT = 5;
  
  /**
   * Percentage of EventUploader's buffer that we reserve for use only
   * for buffer-overflow messages. End events won't use this space (nor
   * will start or leaf events). This ensures that we (almost) always have
   * room to at least record a buffer-overflow message.
   */
  public static final int EVENT_BUFFER_END_EVENT_RESERVED_PERCENT = 1;
  
  /**
   * When uploading a batch of log events to the Scalyr server, we include thread metadata
   * for threads whose oldest event is no older than this. See the reference to this constant
   * in EventUploader.
   */
  public static final long MAX_THREAD_AGE_FOR_UPLOAD_NS = 3600 * 1000000000L;
  
  /**
   * We discard a batch of log events if we've persistently failed to upload it for this many seconds.
   */
  public static final long DISCARD_EVENT_BATCH_AFTER_PERSISTENT_FAILURE_SECONDS = 1200;
  
  /**
   * Maximum rate at which we write diagnostic messages to stdout. Can be overridden
   * by creating a ThresholdLogger with a custom SimpleRateLimiter, and passing it
   * to Logging.setHook(...).
   */
  public static final double MAX_DIAGNOSTIC_MESSAGES_PER_SECOND = 50.0;
  
  /**
   * Maximum burst size for diagnostic messages we write to stdout (the maximum number
   * of messages which can be written before waiting for the budget to replenish). Can
   * be overridden by creating a ThresholdLogger with a custom SimpleRateLimiter, and
   * passing it to Logging.setHook(...).)
   */
  public static final double MAX_DIAGNOSTIC_MESSAGE_BURST = 500.0;
  
  /**
   * Maximum rate at which we write tagKnobFileInvalid messages to stdout. Can be overridden
   * by creating a ThresholdLogger with a custom SimpleRateLimiter, and passing it
   * to Logging.setHook(...).
   */
  public static final double MAX_KNOBFILEINVALID_MESSAGES_PER_SECOND = 10.0;
  
  /**
   * Maximum burst size for tagKnobFileInvalid messages we write to stdout (the maximum number
   * of messages which can be written before waiting for the budget to replenish). Can
   * be overridden by creating a ThresholdLogger with a custom SimpleRateLimiter, and
   * passing it to Logging.setHook(...).)
   */
  public static final double MAX_KNOBFILEINVALID_MESSAGE_BURST = 20.0;
  
  /**
   * Minimum spacing between stdout messages warning that diagnostic messages
   * are being throttled. 
   */
  public static final double DIAGNOSTIC_OVERFLOW_WARNING_INTERVAL_SECS = 10.0;
  
  /**
   * If Knob.get() is called this many times for a given Knob instance, we'll proactively maintain the
   * knob value from then on, allowing subsequent get() calls to return instantly.
   */
  public static final int KNOB_CACHE_THRESHOLD = 100;

  /**
   * If this variable is not null and evaluates to true, then we use the Apache Commons HTTP client library
   * in EventUploader. Otherwise we use java.net.HttpURLConnection. NOTE: using the Apache Commons library
   * increases memory usage somewhat, as we must assemble each request in memory before transmission.
   */
  public static volatile Knob.Boolean useApacheHttpClientForEventUploader = null;

  /**
   * If not null, then we increment this each time we initiate a request to the Scalyr server.
   */
  public static volatile CounterGauge serverInvocationCounter = null;

  /**
   * If not null, then each time we complete a request to the Scalyr server (successfully or otherwise), we increment this by
   * the number of seconds spent waiting on the request.
   */
  public static volatile CounterGauge serverInvocationTimeCounterSecs = null;
}
