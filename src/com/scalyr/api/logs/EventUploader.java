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

package com.scalyr.api.logs;

import com.scalyr.api.TuningConstants;
import com.scalyr.api.internal.ChunkSizeList;
import com.scalyr.api.internal.CircularByteArray;
import com.scalyr.api.internal.Logging;
import com.scalyr.api.internal.Logging.LogLimiter;
import com.scalyr.api.internal.ScalyrUtil;
import com.scalyr.api.json.JSONArray;
import com.scalyr.api.json.JSONObject;
import com.scalyr.api.json.JSONParser.JsonParseException;
import com.scalyr.api.json.RawJson;
import com.scalyr.api.logs.EventFilter.FilterInput;
import com.scalyr.api.logs.EventFilter.FilterOutput;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Internal class which buffers events, and periodically uploads them to the Scalyr Logs service.
 */
public class EventUploader {
  /**
   * Special value used in certain situations for a timestamp parameter; means "atomically assign a monotonic timestamp". All
   * parameters that can accept this value will explicitly document that support. ONLY FOR INTERNAL SCALYR USE.
   */
  public static long ASSIGN_MONOTONIC_TIMESTAMP = -12345_12345_12345L;

  /**
   * Empty attributes object. Should be considered immutable.
   */
  private static final EventAttributes emptyAttributes = new EventAttributes();

  /**
   * Time when this uploader was created. Used as a proxy for the startup time of the
   * containing process.
   */
  private final long launchTimeNs = ScalyrUtil.nanoTime();

  /**
   * Unique ID for this process invocation.
   */
  private final String sessionId;

  /**
   * Client-specified attributes to associate with the events we upload, or null.
   */
  private final EventAttributes serverAttributes;

  /**
   * If true, then we automatically upload events using a timer. If false, then the client must
   * manually initiate upload. Always true except during tests.
   */
  private final boolean autoUpload;

  /**
   * If true, then we include thread names in the metadata we upload to the server. Normally true,
   * but can be disabled by clients who are worried about sensitive data in thread names.
   */
  private final boolean reportThreadNames;

  /**
   * IP address of this server.
   */
  @SuppressWarnings("unused")
  private String ourIpAddress;

  /**
   * Hostname of this server.
   */
  private String ourHostname;

  /**
   * Random number generator used to avoid having multiple clients all upload events at the exact
   * same time. Synchronize access.
   */
  private final Random random = new Random();

  private int uploadSpacingFuzzFactor = random.nextInt(TuningConstants.EVENT_UPLOAD_CHECK_INTERVAL);

  /**
   * While true, we refrain from initiating any attempts to upload data to the Scalyr service.
   */
  public volatile boolean emergencySuspend = false;

  /**
   * ThreadLocal for tracking information per thread -- thread ID, name, etc.
   */
  final ThreadLocal<PerThreadState> threadEvents = new ThreadLocal<PerThreadState>() {
    @Override protected PerThreadState initialValue() {
      Thread thread = Thread.currentThread();
      String threadIdString = Long.toString(thread.getId());
      PerThreadState temp = new PerThreadState(thread.getId(), reportThreadNames ? thread.getName() : "");
      synchronized (threads) {
        ScalyrUtil.Assert(!threads.containsKey(threadIdString), "collision in threads table");
        threads.put(threadIdString, temp);
      }
      return temp;
    }
  };

  /**
   * Return the PerThreadState for the given thread. If this is the first mention of the thread,
   * create a PerThreadState for it.
   */
  PerThreadState getThreadState(String threadId, String threadName) {
    synchronized (threads) {
      PerThreadState state = threads.get(threadId);
      if (state == null) {
        state = new PerThreadState(threadId, threadName);
        threads.put(threadId, state);
      }

      return state;
    }
  }

  /**
   * All outstanding threads for which we have recorded at least one event, indexed by thread ID.
   *
   * TODO: should scavenge records for idle threads.
   */
  private Map<String, PerThreadState> threads = new HashMap<String, PerThreadState>();

  /**
   * Holds the serialized form of all events which have been recorded since the last call to uploadBuffer.
   * Each event is followed by a comma, so the contents of the buffer always look like this:
   *
   *   {...},{...},{...}, ...
   */
  private final CircularByteArray pendingEventBuffer;

  /**
   * Defines a series of chunks in pendingEventBuffer. Each entry is a byte count, describing a
   * range of pendingEventBuffer which begins and ends at event boundaries (an "event boundary"
   * is the beginning of the buffer, or a position just after a comma). The sum of the entries
   * is always exactly equal to the number of bytes in pendingEventBuffer, and no entry is ever
   * larger than TuningConstants.MAX_EVENT_UPLOAD_BYTES.
   *
   * This array is used to partition the buffer into upload-sized chunks.
   *
   * Operations that modify chunkSizes, and/or add or remove data from pendingEventBuffer,
   * are synchronized on chunkSizes.
   */
  private final ChunkSizeList chunkSizes = new ChunkSizeList();

  /**
   * Total number of bytes written to pendingEventBuffer (and added to chunkSizes). Synchronized
   * on chunkSizes.
   */
  private long totalBytesWritten = 0;

  /**
   * True if we've discarded at least one event because we reached pendingEventBuffer
   * is full. Reset whenever an upload completes (thus freeing up memory). This ensures
   * that we don't "stutter" at the edge of the memory boundary, yielding a
   * confusing situation where events are dropped intermittently.
   */
  private boolean pendingEventsReachedLimit = false;

  /**
   * Incremented each time pendingEventsReachedLimit transitions from false to true.
   */
  private volatile int pendingEventsLimitCounter = 0;

  /**
   * Used to rate-limit buffer overflow warnings.
   */
  private static LogLimiter memoryWarnLimiter = new LogLimiter();

  /**
   * True if we have an in-flight upload request to the server.
   */
  private boolean uploadInProgress = false;

  /**
   * Externally supplied Timer used to schedule upload RPCs. Can be null, in which case we use privateTimer.
   */
  private Timer sharedTimer = null;

  /**
   * Timer used to generate upload RPCs. Allocated when the first event is recorded. If sharedTimer is not
   * null, we don't use privateTimer.
   */
  private Timer privateTimer = null;
  private TimerTask uploadTask = null;

  /**
   * If not null, then we perform uploads on this executor. Used to avoid tying up the sharedTimer thread.
   */
  private Executor uploadExecutor = null;

  /**
   * Time when we last initiated an event batch upload, or null if we have not yet
   * started one.
   */
  private Long lastUploadStartMs = ScalyrUtil.currentTimeMillis();

  /**
   * Object used to synchronize access to pendingEventBuffer, pendingEventsReachedLimit,
   * and uploadInProgress.
   */
  private final Object uploadSynchronizer = new Object();

  /**
   * Service we upload to.
   */
  final LogService logService;

  /**
   * We limit memory usage (for buffering events to be uploaded) to approximately this many bytes.
   */
  private final int memoryLimit;

  /**
   * Minimum start-to-start spacing on upload attempts. Adjusted dynamically to throttle requests when the server is having
   * problems.
   */
  volatile double minUploadIntervalMs = TuningConstants.MIN_EVENT_UPLOAD_SPACING_MS;

  /**
   * EventFilter in effect for this uploader, or null if none.
   */
  volatile EventFilter eventFilter;

  /**
   * Specifies whether we call Logging.metaMonitorInfo for events in this EventUploader. True for
   * a normal EventUploader, false for the EventUploader that implements meta-monitoring.
   */
  private final boolean enableMetaMonitoring;

  /**
   * Construct an EventUploader to buffer events and upload them to the given LogService instance.
   * <p>
   * THIS METHOD IS INTENDED FOR INTERNAL USE ONLY.
   */
  public EventUploader(LogService logService, int memoryLimit, String sessionId, boolean autoUpload,
      EventAttributes serverAttributes, boolean enableMetaMonitoring, boolean reportThreadNames) {
    this(logService, memoryLimit, sessionId, autoUpload, serverAttributes, enableMetaMonitoring, reportThreadNames, null, null);
  }

  /**
   * Construct an EventUploader to buffer events and upload them to the given LogService instance.
   * <p>
   * THIS METHOD IS INTENDED FOR INTERNAL USE ONLY.
   */
  public EventUploader(LogService logService, int memoryLimit, String sessionId, boolean autoUpload,
      EventAttributes serverAttributes, boolean enableMetaMonitoring, boolean reportThreadNames,
      Timer sharedTimer_, Executor uploadExecutor_) {
    this.logService = logService;
    this.autoUpload = autoUpload;
    this.reportThreadNames = reportThreadNames;

    this.memoryLimit = memoryLimit;
    pendingEventBuffer = new CircularByteArray(memoryLimit);

    this.sessionId = sessionId;
    this.serverAttributes = serverAttributes;
    this.enableMetaMonitoring = enableMetaMonitoring;

    this.uploadExecutor = uploadExecutor_;
    launchUploadTimer(sharedTimer_);

    // To aid customers being able to quickly see the results of events being uploaded
    // by this host, include a query URL to match them on the Scalyr log servers.
    String serverHost = serverAttributes != null && serverAttributes.containsKey("serverHost") ?
        (String) serverAttributes.get("serverHost") :
        ScalyrUtil.getHostname();
    try {
      Logging.log(Severity.info, Logging.tagEventUploadSession, "Uploading events from " +
          serverHost + ".  You may view events uploaded by this host at " +
          "https://www.scalyr.com/events?mode=log&filter=%24serverHost%3D%27" +
          URLEncoder.encode(serverHost, "UTF-8") +
          "%27&startTime=infinity&linesBefore=100&scrollToEnd=true#scrollTop ");
    } catch (UnsupportedEncodingException e) {
      Logging.log(Severity.error, Logging.tagEventUploadSession,
          "Unsupported encoding seen while trying to output log URL", e);
    }
  }

  /**
   * Add an event to our buffer.
   * <p>
   * THIS METHOD IS INTENDED FOR INTERNAL USE ONLY.
   */
  public void rawEvent(Severity severity, EventAttributes event) {
    threadEvents.get().event(severity, event);
  }

  /**
   * Add an event to our buffer.
   * <p>
   * THIS METHOD IS INTENDED FOR INTERNAL USE ONLY.
   */
  public void rawEvent(Severity severity, EventAttributes event, long timestampNs) {
    threadEvents.get().event(severity, event, timestampNs);
  }

  /**
   * Add an event to our buffer. Associate the event with the given thread information, not the calling thread.
   * <p>
   * THIS METHOD IS INTENDED FOR INTERNAL USE ONLY.
   */
  public void rawEventOnExplicitThread(String threadId, String threadName, Severity severity, EventAttributes attributes) {
    PerThreadState threadState = getThreadState(threadId, threadName);

    synchronized (threadState) {
      threadState.event(severity, attributes);
    }
  }

  /**
   * Add an event to our buffer. Associate the event with the given thread information, not the calling thread.
   * <p>
   * THIS METHOD IS INTENDED FOR INTERNAL USE ONLY.
   */
  public void rawEventOnExplicitThread(String threadId, String threadName, Severity severity, EventAttributes attributes,
      long timestampNs) {
    PerThreadState threadState = getThreadState(threadId, threadName);

    synchronized (threadState) {
      threadState.event(severity, attributes, timestampNs);
    }
  }

  synchronized void terminate() {
    if (uploadTask != null) {
      uploadTask.cancel();
    }

    if (privateTimer != null) {
      privateTimer.cancel();
    }
  }

  /**
   * Force all events recorded to date to be uploaded to the server.
   * <p>
   * (NOTE: this is not foolproof. If the server request fails, or an upload was already in progress
   * when we are called, some events may not be uploaded. This method should only be used where best-effort
   * is ok.)
   */
  synchronized void flush() {
    flush(0L);
  }

  /**
   * Force all events recorded to date to be uploaded to the server.
   * <p>
   * (NOTE: this is not foolproof. If the server request fails, or an upload was already in progress
   * when we are called, some events may not be uploaded. This method should only be used where best-effort
   * is ok.)
   * <p>
   * THIS METHOD IS INTENDED FOR INTERNAL USE ONLY.
   *
   * @param waitTimeMs the maximum number of milliseconds this method should block for while
   *     waiting for the flush to complete.  a non-positive value will cause the method to block
   *     indefinitely.
   * @return true if all events that were enqueued when flush was invoked are actually flushed
   */
  public synchronized boolean flush(long waitTimeMs) {
    long bytesWrittenPriorToFlush;
    synchronized (chunkSizes) {
      bytesWrittenPriorToFlush = totalBytesWritten;
    }

    long deadline = -1;
    if (waitTimeMs > 0)
      deadline = System.currentTimeMillis() + waitTimeMs;

    // Upload a chunk at a time until all data prior to our invocation is gone. Sleep briefly
    // between invocations, in part to avoid frantic spinning if an upload is already in progress
    // (in which case uploadTimerTick returns immediately).
    long sleepMs = 100;
    while (true) {
      synchronized (chunkSizes) {
        long bytesWrittenSinceFlush = totalBytesWritten - bytesWrittenPriorToFlush;
        if (pendingEventBuffer.numBufferedBytes() <= bytesWrittenSinceFlush) {
          return true;
        }
      }

      long remainingMs = deadline - System.currentTimeMillis();
      if ((deadline > 0) && (remainingMs <= 0))
        return false;

      uploadTimerTick(true);

      try {
        Thread.sleep(deadline <= 0 ? sleepMs : Math.min(sleepMs, remainingMs));
        sleepMs = Math.min(sleepMs * 2, 10000);
      } catch (InterruptedException ex) {
        throw new RuntimeException(ex);
      }
    }
  }

  /**
   * Close this EventUploader, shutting down our background timer that uploads events.
   * <p>
   * THIS METHOD IS INTENDED FOR INTERNAL USE ONLY.
   */
  public synchronized void closeAfterTest() {
    if (uploadTask != null) {
      uploadTask.cancel();
      uploadTask = null;
    }

    if (privateTimer != null) {
      privateTimer.cancel();
      privateTimer = null;
    }
  }

  /**
   * Time when we last logged the pendingEventBuffer size.
   */
  long bufLogMs = System.currentTimeMillis();

  void logBuffer() {
    long now = System.currentTimeMillis();
    if (now - bufLogMs > 10000) {
      bufLogMs = now;

      if (enableMetaMonitoring)
        Logging.metaMonitorInfo(new EventAttributes(
            "tag", "pendingEventBuffer",
            "size", pendingEventBuffer.numBufferedBytes()));
      Logging.log(Severity.fine, Logging.tagBufferedEventBytes, Long.toString(pendingEventBuffer.numBufferedBytes()));
    }
  }

  /**
   * This method is called periodically by a timer. If it's been long enough since we last
   * sent a batch of events to the server, we snapshot the events currently buffered (or
   * a portion thereof, if there are too many to upload all at once) and initiate an upload.
   */
  synchronized void uploadTimerTick(boolean bypassWaitTimers) {
    logBuffer();

    final int bufferedBytes;
    RawJson eventsToUpload;

    synchronized (uploadSynchronizer) {
      bufferedBytes = timeToUpload(bypassWaitTimers);
      if (bufferedBytes < 0)
        return;

      eventsToUpload = new RawJson(){
        @Override public void writeJSONBytes(OutputStream out) throws IOException {
          out.write('[');

          // We subtract 1 here to eliminate the trailing comma after the last buffered event.
          pendingEventBuffer.writeOldestBytes(out, bufferedBytes - 1);

          out.write(']');
        }};

      uploadInProgress = true;
      pendingEventsReachedLimit = false;
    }

    // Build a list of thread states to include in this upload request. We include only threads which have
    // had an event in the last hour. Ideally, we'd include exactly the threads for which there is at least
    // one event in this upload batch, but that's a bit tricky to determine.
    List<PerThreadState> threadsSnapshot = new ArrayList<PerThreadState>();
    long timestampThreadhold = ScalyrUtil.nanoTime() - TuningConstants.MAX_THREAD_AGE_FOR_UPLOAD_NS;
    synchronized (threads) {
      for (PerThreadState thread : threads.values()) {
        if (thread.latestEventTimestamp >= timestampThreadhold) {
          threadsSnapshot.add(thread);
        }
      }
    }

    // Sort the threads alphabetically by name -- this ensures a stable order when
    // uploading, which is helpful for tests.
    Collections.sort(threadsSnapshot, new Comparator<PerThreadState>(){
      @Override public int compare(PerThreadState a, PerThreadState b) {
        if (a.name == null || b.name == null) {
          if (a.name == null && b.name == null)
            return 0;
          return (a.name == null) ? -1 : 1;
        }
        return a.name.compareTo(b.name);
      }});

    JSONObject sessionInfo = new JSONObject();

    // Note: any new attributes defined here, should be masked in MetaLogger.
    sessionInfo.put("session", sessionId);
    sessionInfo.put("launchTime", launchTimeNs);
    if (ourHostname != null)
      sessionInfo.put("serverHost", ourHostname);
    // We no longer explicitly report our IP address as a session attribute, as
    // the Scalyr Logs server adds this automatically (under the name "serverIP").
    //
    // if (ourIpAddress != null)
    //   sessionInfo.put("serverIP", ourIpAddress);

    if (serverAttributes != null)
      for (Map.Entry<String, Object> entry : serverAttributes.getEntries())
        sessionInfo.put(entry.getKey(), entry.getValue());

    JSONArray threadInfos = new JSONArray();

    // TODO: only include threads for which we are uploading at least one event.
    for (PerThreadState thread : threadsSnapshot) {
      JSONObject threadInfo = new JSONObject();
      threadInfo.put("id", thread.threadId);
      threadInfo.put("name", thread.name);
      threadInfos.add(threadInfo);
    }

    boolean success = false;
    long start = System.nanoTime();
    long duration = -1L;

    try {
      lastUploadStartMs = ScalyrUtil.currentTimeMillis();
      JSONObject rawResponse;
      try {
        rawResponse = logService.uploadEvents(sessionId, sessionInfo, eventsToUpload, threadInfos);
      } catch (RuntimeException ex) {
        logUploadFailure(ex.toString());
        throw ex;
      } catch (Error ex) {
        logUploadFailure(ex.toString());
        throw ex;
      }

      duration = System.nanoTime() - start;
      try {
        JSONObject parsedResponse = rawResponse;

        // Adjust our upload interval based on the success or failure of this upload request.
        Object rawStatus = parsedResponse.get("status");
        String status = (rawStatus instanceof String) ? (String)rawStatus : "error/server";
        success = status.startsWith("success");
        if (success) {
          logUploadSuccess();

          minUploadIntervalMs *= TuningConstants.UPLOAD_SPACING_FACTOR_ON_SUCCESS;
          minUploadIntervalMs = Math.max(minUploadIntervalMs, TuningConstants.MIN_EVENT_UPLOAD_SPACING_MS);

          synchronized (uploadSynchronizer) {
            synchronized (chunkSizes) {
              ScalyrUtil.Assert(chunkSizes.getFirst() == bufferedBytes,
                  "event buffer chunk was resized while being uploaded");
              chunkSizes.removeFirst();
            }

            pendingEventBuffer.discardOldestBytes(bufferedBytes);
            pendingEventsReachedLimit = false;
          }
        } else {
          logUploadFailure("Server response had bad status [" + rawStatus + "]; complete text: " + parsedResponse.toString());

          Logging.log(EventUploader.this, Severity.warning, Logging.tagServerError,
              "Bad response from Scalyr Logs (status [" + status + "], message [" +
              parsedResponse.get("message") + "])");

          // Note that we back off for all errors, not just error/server/backoff. Other errors are liable to
          // be systemic, and there's little reason to retry an upload frequently in the face of systemic errors.
          minUploadIntervalMs *= TuningConstants.UPLOAD_SPACING_FACTOR_ON_BACKOFF;
          minUploadIntervalMs = Math.min(minUploadIntervalMs, TuningConstants.MAX_EVENT_UPLOAD_SPACING_MS);
        }
      } catch (JsonParseException ex) {
        logUploadFailure(ex.toString());

        // This shouldn't occur, as the underlying service framework verifies that the server's
        // response is valid JSON.
        throw new RuntimeException(ex);
      }
    } finally {
      synchronized (uploadSynchronizer) {
        uploadInProgress = false;
      }
      if (duration == -1L)
        duration = System.nanoTime() - start;

      if (enableMetaMonitoring)
        Logging.metaMonitorInfo(new EventAttributes(
            "tag", "clientUploadEvents",
            "size", bufferedBytes,
            "duration", duration,
            "success", success));
      Logging.log(Severity.fine, Logging.tagEventUploadOutcome,
          "{\"size\": " + bufferedBytes + ", \"duration\": " + duration + ", \"success\", " + success + "}");
    }
  }

  private boolean loggedUploadSuccess = false;

  /**
   * If the most recent upload attempt failed, this holds the millisecond timestamp when the current string of
   * consecutive failures began. Otherwise null.
   */
  private Long uploadFailuresStartMs = null;

  /**
   * This method is called whenever we successfully upload a batch of events to the server.
   */
  void logUploadSuccess() {
    uploadFailuresStartMs = null;

    if (!loggedUploadSuccess) {
      // Write a note to stdout indicating that we've successfully uploaded a batch of events to
      // the server. We do this only once (for the first successful upload). The agent.sh script
      // uses this to see whether the relay has come up cleanly.
      loggedUploadSuccess = true;
      System.out.println("UPLOAD SUCCESS: the first batch of events has been successfully uploaded to the Scalyr server.");
    }
  }

  /**
   * This method is called whenever we experience a failure while attempting to upload batch of events to the server.
   * It is called for all failure modes, from local exceptions to network problems to error codes returned from the
   * server.
   */
  void logUploadFailure(String message) {
    long nowMs = ScalyrUtil.currentTimeMillis();
    if (uploadFailuresStartMs == null)
      uploadFailuresStartMs = nowMs;
    else {
      if (_discardBatchesAfterPersistentFailures &&
          nowMs - uploadFailuresStartMs >= TuningConstants.DISCARD_EVENT_BATCH_AFTER_PERSISTENT_FAILURE_SECONDS * 1000) {
        int firstChunkSize;

        // We've persistently failed to upload this chunk for a long time. There may be something fundamentally
        // wrong with it. In any event, the current server implementation is unable to accept events that are more
        // than a few minutes old. Either way, we have nothing to gain by holding onto this data chunk. So we'll
        // discard it, in hopes of helping the upload process to resume.
        synchronized (uploadSynchronizer) {
          synchronized (chunkSizes) {
            firstChunkSize = chunkSizes.getFirst();
            chunkSizes.removeFirst();
          }
          pendingEventBuffer.discardOldestBytes(firstChunkSize);
          pendingEventsReachedLimit = false;
        }

        Logging.log(EventUploader.this, Severity.warning, Logging.tagLogBufferOverflow,
            "Discarding an event batch of size " + firstChunkSize + " bytes, because we have been persistently unable to upload it. Latest error: ["
            + message + "]");
      }
    }

    if (!loggedUploadSuccess) {
      // Write a note to stdout indicating that we've had a failed attempt to upload a batch of events to
      // the server. We do this only until the first successful upload. The agent.sh script uses this
      // to see whether the relay has come up cleanly.
      System.out.println("UPLOAD FAILURE: " + message);
    }
  }

  /**
   * If it's time to initiate a new upload batch, return the number of event payload bytes
   * (from pendingEventBuffer) to upload. Otherwise return -1.
   *
   * Caller must hold the locks on "this" and uploadSynchronizer.
   */
  private int timeToUpload(boolean bypassWaitTimers) {
    // If we have an upload request in flight, don't initiate another one now.
    if (uploadInProgress)
      return -1;

    if (emergencySuspend)
      return -1;

    long nowMs = ScalyrUtil.currentTimeMillis();

    // Enforce a minimum start-to-start spacing between uploads, to avoid overloading the server.
    if (!bypassWaitTimers && lastUploadStartMs != null) {
      long msSinceLastUpload = nowMs - lastUploadStartMs;
      if (msSinceLastUpload < minUploadIntervalMs) {
        return -1;
      }

      if (TuningConstants.adjustableEventUploadSpacingFloorMs != null &&
          msSinceLastUpload < TuningConstants.adjustableEventUploadSpacingFloorMs.get()) {
        return -1;
      }

    }

    // Get the size of the chunk to upload.
    synchronized (chunkSizes) {
      int bufferedBytes = chunkSizes.getFirst();
      if (bufferedBytes == 0) {
        // nothing to upload
        pendingEventsReachedLimit = false;
        return -1;
      }

      // Wait until the buffer is reasonably full, or it has been a fair while since we last initiated
      // an upload.
      boolean bufferFairlyFull = (bufferedBytes > _eventUploadByteThreshold);
      boolean itsBeenAWhile = (lastUploadStartMs == null
          || nowMs - lastUploadStartMs >= TuningConstants.EVENT_UPLOAD_TIME_THRESHOLD_MS + uploadSpacingFuzzFactor);

      if (bypassWaitTimers || bufferFairlyFull || itsBeenAWhile) {
        // Prevent further data from being added to this chunk.
        chunkSizes.closeFirst();

        synchronized (random) {
          uploadSpacingFuzzFactor = random.nextInt(TuningConstants.EVENT_UPLOAD_CHECK_INTERVAL);
        }
        return bufferedBytes;
      } else {
        return -1;
      }
    }
  }

  /**
   * If true, then normal timer-based event uploading is disabled. For use only in tests.
   * Should not be used by client applications (this means you!).
   */
  public static volatile boolean _disableUploadTimer = false;

  /**
   * If false, then we ignore TuningConstants.DISCARD_EVENT_BATCH_AFTER_PERSISTENT_FAILURE_SECONDS.
   * Always true except during tests.
   */
  public static volatile boolean _discardBatchesAfterPersistentFailures = true;

  private synchronized void launchUploadTimer(Timer sharedTimer) {
    this.sharedTimer = sharedTimer;

    ourHostname = ScalyrUtil.getHostname();
    ourIpAddress = ScalyrUtil.getIpAddress();

    if (sharedTimer == null && privateTimer == null) {
      privateTimer = new Timer("EventUploader", true);
    }

    if (autoUpload && uploadTask == null) {
      uploadTask = new TimerTask(){
        @Override public void run() {
          try {
            if (!_disableUploadTimer) {
              if (uploadExecutor != null) {
                uploadExecutor.execute(new Runnable() {
                  @Override public void run() {
                    uploadTimerTick(false);
                  }
                });
              } else {
                uploadTimerTick(false);
              }
            }
          } catch (Throwable ex) {
            Logging.log(EventUploader.this, Severity.warning, Logging.tagInternalError, "Exception in Logs upload timer", ex);
          }
        }};

      // Launch a task to periodically check whether it's time to upload events. Randomize the starting time,
      // to help ensure that clients aren't all uploading at the same time. (Especially important in load tests.)
      int randomDelay;
      synchronized (random) {
        randomDelay = random.nextInt(TuningConstants.EVENT_UPLOAD_CHECK_INTERVAL) + 1;
      }

      Timer timer = (sharedTimer != null) ? sharedTimer : privateTimer;
      timer.schedule(uploadTask, randomDelay, TuningConstants.EVENT_UPLOAD_CHECK_INTERVAL);
    }
  }

  /**
   * Information that we store per running thread.
   */
  class PerThreadState {
    /**
     * Thread.getId() for the thread whose events we store.
     */
    final String threadId;

    /**
     * Thread name.
     */
    final String name;

    /**
     * Number of start events in this thread that haven't yet been balanced by an end event.
     * Includes events which are discarded by a filter or due to buffer overflow. Does not
     * include the implicit session or thread spans.
     */
    private int spanNesting;

    /**
     * Number of start events in this thread that must be balanced by an end event, to reach
     * a point where no enclosing start event was discarded by a filter. In other words, this
     * is equal to spanNesting, minus the contiguous sequence of outermost non-discarded spans.
     *
     * This value does not reflect events which were discarded due to the event buffer filling
     * up (see bufferLimitDiscardSpanNesting).
     */
    private int filterDiscardSpanNesting;

    /**
     * Like filterDiscardSpanNesting, but reflects discards due to buffer overflow rather than
     * filters.
     *
     * Buffer overflow checks occur after event filtering, so if an event is discarded by a filter,
     * we don't attempt to place it in the buffer and it won't be reflected here.
     */
    private int bufferLimitDiscardSpanNesting;

    /**
     * Holds the value of pendingEventsLimitCounter when we last discarded an event in this thread,
     * or -1 if we have never done so.
     */
    private int eventDiscardGeneration = -1;

    /**
     * Nanosecond timestamp of the most recent event in this thread, or 0 if there have never been any
     * events in the thread.
     */
    private long latestEventTimestamp = 0;

    PerThreadState(long threadId, String name) {
      this(Long.toString(threadId), name);
    }

    PerThreadState(String threadId, String name) {
      this.threadId = threadId;
      this.name = name;
    }

    /**
     * Denormalize the end-span event by adding span's start attributes to the incoming `attributes`,
     * then add it to the buffer just like a non-span event.
     */
    @Deprecated
    void end(Span span, EventAttributes attributes) {
      EventAttributes mergedAttributes = new EventAttributes();

      if (span.startAttributes != null) {
        mergedAttributes.addAll(span.startAttributes);
      }

      if (attributes != null) {
        mergedAttributes.addAll(attributes);
      }

      mergedAttributes.put("latency", TimeUnit.NANOSECONDS.toMillis(ScalyrUtil.nanoTime() - span.startTime));

      event(span.severity, mergedAttributes, ASSIGN_MONOTONIC_TIMESTAMP, span.startTime);
    }

    /**
     * Add a non-span event to the buffer.
     */
    void event(Severity severity, EventAttributes attributes) {
      event(severity, attributes, ASSIGN_MONOTONIC_TIMESTAMP);
    }

    /**
     * Add a non-span event to the buffer, with an explicitly specified timestamp.
     *
     * @para timestampNs Timestamp for this event, or ASSIGN_MONOTONIC_TIMESTAMP.
     */
    void event(Severity severity, EventAttributes attributes, long timestampNs) {
      event(severity, attributes, timestampNs, null);
    }

    /**
     * Add a non-span event to the buffer, with an explicitly specified timestamp and startTs
     */
    void event(Severity severity, EventAttributes attributes, long timestampNs, Long startNs) {
      convertAndAddToBuffer(timestampNs, LogService.SPAN_TYPE_LEAF, severity, attributes, startNs,
        memoryLimit * TuningConstants.EVENT_BUFFER_RESERVED_PERCENT / 100, false);
    }


    /**
     * Run the given event through the EventFilter, convert it to JSON, and add it to our event buffer.
     * Return false if the event is discarded by a filter.
     *
     * Don't record the event if it would leave less than reservedBufferSpace bytes remaining in
     * the buffer.
     *
     * If isOverflowMessage is true, then this is a buffer-overflow message, which bypasses filters
     * and certain other checks.
     *
     * @param timestamp The timestamp for this event. Can be ASSIGN_MONOTONIC_TIMESTAMP, in which case we will assign a timestamp
     *     by calling getMonotonicNanos.
     */
    private ResultAndTimestamp convertAndAddToBuffer(long timestamp, int spanType, Severity severity,
        EventAttributes attributes, Long startTs, int reservedBufferSpace, boolean isOverflowMessage) {
      EventFilter localFilter = eventFilter;
      if (localFilter != null && !isOverflowMessage) {
        // On a few code paths here, we need to know the event's timestamp... but if it's ASSIGN_MONOTONIC_TIMESTAMP, we can't
        // assign a proper value until entering a synchronized block later on. So for now, we'll use an approximate value. The
        // places where this approximate value might be used are not critical.
        long approximateTimestamp = timestamp == ASSIGN_MONOTONIC_TIMESTAMP ? ScalyrUtil.nanoTime() : timestamp;

        FilterInput filterInput = new FilterInput();
        filterInput.threadId = threadId;

        //noinspection deprecation
        filterInput.timestampNs = approximateTimestamp;

        filterInput.spanType = spanType;
        filterInput.severity = severity;
        filterInput.attributes = attributes != null ? attributes : emptyAttributes;
        filterInput.isTopLevel = (spanNesting == 0);
        filterInput.inDiscardedSpan = (filterDiscardSpanNesting > 0);

        FilterOutput filterOutput = new FilterOutput(filterInput);
        localFilter.filter(filterInput, filterOutput);

        if (filterOutput.discardEvent)
          return new ResultAndTimestamp(ConvertAndAddResult.discardedByFilter, approximateTimestamp);

        if (bufferLimitDiscardSpanNesting > 0)
          return new ResultAndTimestamp(ConvertAndAddResult.discardedByEventOverflow, approximateTimestamp);

        severity = filterOutput.severity;
        if (filterOutput.attributes != null) {
          attributes = filterOutput.attributes;
        }
      }

      JSONObject json = new JSONObject();

      json.put("thread", threadId);
      json.put("type", spanType);
      json.put("sev", severity.ordinal());

      if (attributes != null && attributes.size() > 0) {
        JSONObject jsonAttrs = new JSONObject();

        for (Map.Entry<String, Object> entry : attributes.getEntries()) {
          Object value = entry.getValue();
          if (value instanceof String && ((String)value).length() > TuningConstants.MAXIMUM_EVENT_ATTRIBUTE_LENGTH) {
            value = ((String)value).substring(0, TuningConstants.MAXIMUM_EVENT_ATTRIBUTE_LENGTH - 3) + "...";
          }
          jsonAttrs.put(entry.getKey(), value);
        }

        json.put("attrs", jsonAttrs);
      }

      if (startTs != null)
        json.put("startTS", startTs);

      // We defer population of the timestamp field in the JsonObject to within addEventToBuffer, so that getMonotonicNanos()
      // can be called inside the synchronized block -- see discussion in that method.

      return addEventToBuffer(json, timestamp, spanType == LogService.SPAN_TYPE_END, reservedBufferSpace, isOverflowMessage);
    }

    /**
     * @param eventJson JSON structure for this event, minus the "ts" field, which we will populate.
     * @param timestamp Nanosecond timestamp for this event. Can be ASSIGN_MONOTONIC_TIMESTAMP.
     * @param isEndEvent True for end events, false for start or non-span events.
     */
    private ResultAndTimestamp addEventToBuffer(JSONObject eventJson, long timestamp, boolean isEndEvent, int reservedBufferSpace,
        boolean isOverflowMessage) {
      boolean discardingDueToMemoryLimit;
      synchronized (uploadSynchronizer) {
        // Call getMonotonicNanos inside the synchronized block, so that events are added to the buffer in the order in which their
        // timestamps are assigned.
        if (timestamp == ASSIGN_MONOTONIC_TIMESTAMP)
          timestamp = getMonotonicNanos();

        // Note that we store the timestamp as a string, not a number. This is because some JSON packages
        // convert all numbers to floating point, and a 64-bit floating point value doesn't have sufficient
        // precision to represent a nanosecond timestamp. We take a similar precaution for the thread ID.
        eventJson.put("ts", Long.toString(timestamp));
        latestEventTimestamp = timestamp;

        if (pendingEventsReachedLimit && !isEndEvent && !isOverflowMessage) {
          // If pendingEventsReachedLimit is true, we discard all events, to avoid
          // "stuttering" (letting in some events and not others) at the memory boundary.
          // However, we still let end events through, to maintain the integrity of span
          // nesting.
          discardingDueToMemoryLimit = true;
        } else if (bufferLimitDiscardSpanNesting > 0 && !isOverflowMessage) {
          // Don't upload any event that occurs inside a discarded span, including the
          // end event.
          discardingDueToMemoryLimit = true;
        } else {
          ByteArrayOutputStream stream = new ByteArrayOutputStream();
          try {
            eventJson.writeJSONBytes(stream);
          } catch (IOException ex) {
            // This should never happen, since we're working with in-memory data.
            throw new RuntimeException(ex);
          }
          stream.write(',');
          byte[] serialized = stream.toByteArray();
          int serializedLen = serialized.length;

          synchronized (chunkSizes) {
            if (pendingEventBuffer.append(serialized, 0, serializedLen, reservedBufferSpace)) {
              chunkSizes.append(serializedLen, _maxEventUploadBytes);
              totalBytesWritten += serializedLen;
              discardingDueToMemoryLimit = false;
            } else {
              discardingDueToMemoryLimit = true;
              pendingEventsReachedLimit = true;
              pendingEventsLimitCounter++;
            }
          }
        }
      }

      if (discardingDueToMemoryLimit && !isOverflowMessage) {
        int snapshot = pendingEventsLimitCounter;
        if (eventDiscardGeneration != snapshot) {
          eventDiscardGeneration = snapshot;

          // Record a log event noting that we had to discard some events.
          convertAndAddToBuffer(ASSIGN_MONOTONIC_TIMESTAMP, LogService.SPAN_TYPE_LEAF, Severity.warning,
              new EventAttributes("tag", "eventBufferOverflow", "message", "Discarding log records due to buffer overflow"),
              null, 0, true);
        }

        if (memoryWarnLimiter.allow(TuningConstants.EVENT_UPLOAD_MEMORY_WARNING_INTERVAL_MS)) {
          Logging.log(EventUploader.this, Severity.warning, Logging.tagLogBufferOverflow,
              "com.scalyr.api.logs: Discarding event, as buffer size of "
              + memoryLimit + " bytes has been reached.");
        }

        return new ResultAndTimestamp(ConvertAndAddResult.discardedByEventOverflow, timestamp);
      } else {
        return new ResultAndTimestamp(ConvertAndAddResult.success, timestamp);
      }
    }
  }

  /**
   * See TuningConstants.MAX_EVENT_UPLOAD_BYTES. Sometimes modified in tests. Should not be used
   * by client applications (this means you!).
   */
  public static volatile int _maxEventUploadBytes = TuningConstants.MAX_EVENT_UPLOAD_BYTES;

  /**
   * See TuningConstants.EVENT_UPLOAD_BYTE_THRESHOLD. Sometimes modified in tests. Should not be used
   * by client applications (this means you!).
   */
  public static volatile int _eventUploadByteThreshold = TuningConstants.EVENT_UPLOAD_BYTE_THRESHOLD;

  /**
   * Most recently assigned timestamp. Used to ensure that timestamps are strictly increasing (within
   * a given process): nanoTime() can sometimes run backwards.
   */
  private long lastTimestamp = ScalyrUtil.nanoTime();

  /**
   * Object used to synchronize access to lastTimestamp.
   */
  private final Object timestampLocker = new Object();

  /**
   * Return the current time (in nanoseconds since the epoch), but strictly greater than any
   * previous result from this method.
   *
   * Don't call this method directly unless you know what you are doing; it is primarily intended for internal use.
   */
  public long getMonotonicNanos() {
    long now = ScalyrUtil.nanoTime();
    synchronized (timestampLocker) {
      // TODO: warn when nanoTime runs backwards.
      long timestamp = Math.max(lastTimestamp + 1, now);
      lastTimestamp = timestamp;

      return timestamp;
    }
  }

  enum ConvertAndAddResult {
    success,
    discardedByFilter,
    discardedByEventOverflow
  }

  /**
   * Encapsulates a ConvertAndAddResult and the timestamp that was assigned to the event.
   */
  static class ResultAndTimestamp {
    final ConvertAndAddResult result;
    final long eventTimestamp;

    ResultAndTimestamp(ConvertAndAddResult result, long eventTimestamp) {
      this.result = result;
      this.eventTimestamp = eventTimestamp;
    }
  }
}
