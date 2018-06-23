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

package com.scalyr.api.knobs;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Date;

import com.scalyr.api.Converter;
import com.scalyr.api.TuningConstants;
import com.scalyr.api.internal.Logging;
import com.scalyr.api.internal.ScalyrUtil;
import com.scalyr.api.internal.Sleeper;
import com.scalyr.api.json.JSONObject;
import com.scalyr.api.json.JSONParser;
import com.scalyr.api.json.JSONValue;
import com.scalyr.api.logs.Severity;

/**
 * A file hosted on the Knobs service.
 */
class HostedConfigurationFile extends ConfigurationFile {
  private final File cacheFile;
  
  private final KnobService knobService;
  
  /**
   * Number of seconds to wait when blocking until an updated version of a configuration file becomes
   * available. After this time, the request will complete and we'll issue a new request. We use a finite
   * value to avoid connection timeouts and the like.
   */
  private static final int MAX_WAIT_TIME = 30;
  
  /**
   * @param cacheDir If not null, then we look for a copy of the configuration file in this directory, and
   *     initialize our state based on that file until we first retrieve it from the server. We also
   *     update the file with a copy of each file fetched from the server.
   */
  protected HostedConfigurationFile(KnobService knobService, String filePath, File cacheDir) {
    super(filePath);
    
    this.knobService = knobService;
    
    if (cacheDir != null) {
      cacheFile = new File(cacheDir, filePath.replace("/", "__"));
      fetchInitialStateFromCacheFile();
    } else {
      cacheFile = null;
    }
    
    initiateAsyncFetch(null);
  }
  
  @Override public String toString() {
    return "<hosted configuration file \"" + pathname + "\">";
  }
  
  private void fetchInitialStateFromCacheFile() {
    if (!cacheFile.exists())
      return;
    
    // Retrieve the file's content, and record a new FileState.
    String cacheFileContent;
    try {
      cacheFileContent = ScalyrUtil.readFileContent(cacheFile);
    } catch (UnsupportedEncodingException ex) {
      Logging.log(Severity.warning, Logging.tagKnobCacheIOError,
          "Error reading cache file [" + cacheFile.getAbsolutePath() + "]", ex);
      return;
    } catch (IOException ex) {
      Logging.log(Severity.warning, Logging.tagKnobCacheIOError,
          "Error reading cache file [" + cacheFile.getAbsolutePath() + "]", ex);
      return;
    }
    
    int headerEnd = cacheFileContent.indexOf('}');
    if (headerEnd <= 0) {
      Logging.log(Severity.warning, Logging.tagKnobCacheCorrupt,
          "Cachefile [" + cacheFile.getAbsolutePath() + "] does not contain a proper header");
      return;
    }
    
    try {
      JSONObject header = (JSONObject) JSONParser.parse(cacheFileContent.substring(0, headerEnd + 1));
      
      long version = (long) Converter.toLong(header.get("version"));
      if (version == 0) {
        setFileState(new FileState(version, null, null, null));
      } else {
        long createDate = Converter.toLong(header.get("createDate"));
        long modDate    = Converter.toLong(header.get("modDate"));
        setFileState(new FileState(version, cacheFileContent.substring(headerEnd + 1), new Date(createDate), new Date(modDate)));
      }
    } catch (Exception ex) {
      Logging.log(Severity.warning, Logging.tagKnobCacheIOError,
          "Error reading cache file [" + cacheFile.getAbsolutePath() + "]", ex);
    }
  }
  
  @Override protected void noteNewState() {
    super.noteNewState();
    
    if (cacheFile != null) {
      // Record a cached copy of our current state.
      
      JSONObject header = new JSONObject();
      header.put("version", fileState.version);
      if (fileState.content != null) {
        header.put("createDate", fileState.creationDate    .getTime());
        header.put("modDate",    fileState.modificationDate.getTime());
      }
      
      StringBuilder sb = new StringBuilder();
      sb.append(JSONValue.toJSONString(header));
      if (fileState.content != null) {
        sb.append(fileState.content);
      }
      
      ScalyrUtil.writeStringToFile(sb.toString(), cacheFile);
    }
  }
  
  // private static final AtomicInteger idCounter = new AtomicInteger(0);
  
  private void initiateAsyncFetch(final Long expectedVersion_) {
    // final int id = idCounter.incrementAndGet();
    // Logging.log("initiateAsyncFetch " + id + ": path [" + filePath + "], expectedVersion " + expectedVersion);
    
    ScalyrUtil.asyncApiExecutor.execute(new Runnable(){
      Long expectedVersion = expectedVersion_;
      
      @Override public void run() {
        int retryInterval = TuningConstants.MINIMUM_FETCH_INTERVAL;
        
        while (!isClosed()) {
          try {
            long startTime = ScalyrUtil.currentTimeMillis();
            String rawResponse = knobService.getFile(getPathname(), expectedVersion, MAX_WAIT_TIME);
          
            JSONObject response = (JSONObject) JSONParser.parse(rawResponse);
          
            Object statusObj = response.get("status");
            String status = (statusObj != null) ? statusObj.toString() : "error/server/missingStatus";
          
            Object stalenessSlop = response.get("stalenessSlop");
            long stalenessSlopLong = (stalenessSlop != null) ? Converter.toLong(stalenessSlop) : 0;
          
            // Logging.log("initiateAsyncFetch " + id + ": status " + status);
          
            if (status.startsWith("success")) {
              // After a successful response, we quickly issue a new request. We pause slightly
              // simply as a safety measure. Normally, we would not expect a rapid-fire sequence
              // of successful responses -- we should usually wait for MAX_WAIT_TIME. The delay
              // here ensures that even if something goes wrong, we'll still issue at most a
              // couple of requests per second.
              retryInterval = TuningConstants.MINIMUM_FETCH_INTERVAL;
              
              if (status.startsWith("success/noSuchFile")) {
                updateStalenessBound(stalenessSlopLong + ScalyrUtil.currentTimeMillis() - startTime);
                setFileState(new FileState(0, null, null, null));
              } else if (status.startsWith("success/unchanged")) {
                updateStalenessBound(stalenessSlopLong + ScalyrUtil.currentTimeMillis() - startTime);
              } else {
                updateStalenessBound(stalenessSlopLong + ScalyrUtil.currentTimeMillis() - startTime);
                setFileState(new FileState(Converter.toLong(response.get("version")),
                    (String) response.get("content"),
                    new Date((long)Converter.toLong(response.get("createDate"))),
                    new Date((long)Converter.toLong(response.get("modDate"   )))));
              }
            } else {
              // After any sort of error or backoff response, retry after 5 seconds, successively
              // doubling up to a maximum of 1 minute. 
              retryInterval = increaseBackoff(retryInterval);

              if (status.startsWith("error/server/backoff")) {
                Logging.log(Severity.warning, Logging.tagServerBackoff,
                    "Configuration server returned status [" + status + "], message [" +
                    response.get("message") + "]; backing off");
                
              } else {
                Logging.log(Severity.warning, Logging.tagServerError,
                    "Bad response from configuration server (status [" + status + "], message [" +
                    response.get("message") + "])");
              }
            }
          } catch (Exception ex) {
            // After any sort of error or backoff response, retry after 5 seconds, successively
            // doubling up to a maximum of 1 minute.
            retryInterval = increaseBackoff(retryInterval);

            Logging.log(Severity.warning, Logging.tagServerError,
                "Error communicating with the configuration server(s) [" +
                knobService.getServerAddresses() + "] to fetch file [" + getPathname() + "]",
                ex);
          }
        
          // TODO: throttle requests, to avoid runaway loops in the case of connectivity problems or
          // other systemic problems. E.g. we might limit ourselves to 5 invocations per minute.
          // For now, the retryInterval prevents excessive runaway activity.
          Sleeper.instance.sleep(retryInterval);
        
          // Logging.log("initiateAsyncFetch " + id + ": recursing");
          synchronized (this) {
            expectedVersion = (fileState != null ? fileState.version : null);
          }
        }
      }

      private int increaseBackoff(int retryInterval) {
        if (retryInterval < TuningConstants.MINIMUM_FETCH_INTERVAL_AFTER_ERROR)
          return TuningConstants.MINIMUM_FETCH_INTERVAL_AFTER_ERROR;
        else
          return Math.min(retryInterval*2, TuningConstants.MAXIMUM_FETCH_INTERVAL);
      }
    });
  }
}
