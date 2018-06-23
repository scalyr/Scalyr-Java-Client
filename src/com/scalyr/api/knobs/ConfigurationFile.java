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

import com.scalyr.api.Callback;
import com.scalyr.api.ScalyrDeadlineException;
import com.scalyr.api.ScalyrException;
import com.scalyr.api.internal.Logging;
import com.scalyr.api.internal.ScalyrUtil;
import com.scalyr.api.json.JSONObject;
import com.scalyr.api.json.JSONParser;
import com.scalyr.api.json.JSONParser.JsonParseException;
import com.scalyr.api.logs.Severity;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Abstract interface for a configuration file.
 * <p>
 * Represents a particular pathname in a particular file repository.
 * <p>
 * An instance of ConfigurationFile typically holds internal / system resources,
 * such as a file handle or a polling thread. To release such resources, you
 * should call close() when you are finished with a ConfigurationFile. If you will
 * retain the ConfigurationFile until your process terminates, there is no need to
 * call close().
 */
public abstract class ConfigurationFile {
  /**
   * Counter which is incremented whenever any ConfigurationFile's contents change.
   */
  static AtomicInteger globalChangeCounter = new AtomicInteger(0);
  
  /**
   * Our pathname within the repository.
   */
  protected final String pathname;
  
  /**
   * The most recently observed state of this file. If we have not yet retrieved any version of
   * the file, holds null.
   */
  protected FileState fileState = null;
  
  /**
   * The result of parsing fileState.content as JSON. Null if fileState or fileState.content are
   * null. Will also be null if we haven't yet attempted to parse the current file.
   */
  private JSONObject cachedJson = null;
  
  /**
   * Parallels cachedJson. If fileState.content is not a valid JSON file, this field will hold an
   * exception object. Will be null if we haven't yet attempted to parse the current file.
   */
  private BadConfigurationFileException cachedJsonError = null;
  
  /**
   * All callbacks which have been registered to be notified when the file state changes.
   */
  private Set<Callback<ConfigurationFile>> updateListeners = new HashSet<Callback<ConfigurationFile>>();
  
  /**
   * Time when we last heard a definitive update from the server (or other base repository) regarding
   * the state of this file, or null if we never have. Allows us to derive a (very conservative) upper
   * bound on the staleness of our cached copy of the file.
   */
  private Long timeOfLastPoll = null;
  
  /**
   * True if close() has been called on this file.
   */
  private boolean closed;

  /**
   * Normally false. LocalConfigurationFile has logic so that if a file contains valid JSON and is then changed to invalid
   * JSON, we will ignore the update and continue using the last valid JSON version of the file. When the file is in that
   * state, this field will be true. If the file is updated to contain valid JSON again, this field is cleared.
   */
  public volatile boolean maskingInvalidJson;

  /**
   * Return a ConfigurationFileFactory that retrieves files from the local filesystem.
   * 
   * @param rootDir Directory in which configuration files are located. Pathnames are interpreted
   *     relative to this directory.
   * @param pollIntervalMs How often to check for changes to the filesystem, in milliseconds.
   */
  public static ConfigurationFileFactory makeLocalFileFactory(final File rootDir, final int pollIntervalMs) {
    return new ConfigurationFileFactory(){
      @Override protected ConfigurationFile createFileReference(String filePath) {
        return new LocalConfigurationFile(rootDir, filePath, pollIntervalMs);
      }
    };
  }
  
  /**
   * Return a value which increments whenever a change is observed in any configuration file. This
   * can be used to invalidate caches derived from a configuration file.
   */
  public static int getGlobalChangeCounter() {
    return globalChangeCounter.get();
  }
  
  protected ConfigurationFile(String pathname) {
    this.pathname = pathname;
  }
  
  /**
   * Close the file. We cease checking for changes on the file. All internal/system
   * resources associated with the file, such as file handles or polling threads, are
   * released.
   * <p>
   * After calling this method, you should not invoke any other methods on the
   * ConfigurationFile (except for close(), isClosed(), or removeUpdateListener()).
   */
  public synchronized void close() {
    closed = true;
  }
  
  /**
   * Return true if close() has been called on this file.
   */
  public synchronized boolean isClosed() {
    return closed;
  }
  
  private void verifyNotClosed() {
    if (isClosed())
      throw new ScalyrException("Access to closed configuration file " + toString());
  }
  
  /**
   * Return an upper bound, in milliseconds, on the "staleness" of our local view of the file.
   * <p>
   * This is a very conservative upper bound -- the actual staleness is rarely more than a second
   * or two. If we have not yet retrieved an initial copy of the file, returns null.
   */
  public synchronized Long getStalenessBound() {
    verifyNotClosed();
    
    if (timeOfLastPoll == null)
      return null;
    
    return ScalyrUtil.currentTimeMillis() - timeOfLastPoll;
  }
  
  protected synchronized void updateStalenessBound(long slopMs) {
    timeOfLastPoll = ScalyrUtil.currentTimeMillis() + slopMs;
  }
  
  /**
   * Replace our current FileState with the new value, and notify everyone who needs notifying. If
   * the new FileState has an older verson than our current state, do nothing.
   */
  protected void setFileState(FileState value) {
    List<Callback<ConfigurationFile>> listenerSnapshot;
    
    synchronized (this) {
      if (isClosed())
        return;
      
      if (fileState != null && value.version != 0 && fileState.version >= value.version) {
        return;
      }
      
      globalChangeCounter.incrementAndGet();
      fileState = value;
      cachedJson = null;
      cachedJsonError = null;
      noteNewState();
      notifyAll();
      
      listenerSnapshot = new ArrayList<Callback<ConfigurationFile>>(updateListeners);
    }
    
    for (Callback<ConfigurationFile> updateListener : listenerSnapshot) {
      updateListener.run(this);
    }
  }
  
  /**
   * Invoked whenever setFileState receives a truly new (not out-of-order) update to the file state.
   */
  protected void noteNewState() {
  }
  
  /**
   * Return the pathname for this ConfigurationFile.
   */
  public String getPathname() {
    return pathname;
  }
  
  /**
   * True if we have fetched at least one version of the file, i.e. if get() will not block.
   */
  public synchronized boolean hasState() {
    verifyNotClosed();
    
    return fileState != null;
  }
  
  /**
   * Return most recently observed state of the file.
   * 
   * If we have not yet managed to fetch the file, block until it can be fetched.
   */
  public FileState get() {
    return blockUntilValue(null, null);
  }
  
  /**
   * Return most recently observed state of the file.
   * <p>
   * If we have not yet managed to fetch the file, block until it can be retrieved or the
   * specified time interval elapses. If the time interval elapses, we throw a
   * ScalyrDeadlineException.
   * 
   * @param timeoutInMs Maximum time to block, in milliseconds. Null means no timeout (wait
   *     indefinitely).
   * 
   * @throws ScalyrDeadlineException
   */
  public FileState getWithTimeout(Long timeoutInMs) throws ScalyrDeadlineException {
    return getWithTimeout(timeoutInMs, timeoutInMs);
  }
  
  /**
   * Return the most recently observed state of the file.
   * <p>
   * If we have not yet managed to fetch the file, this will block until it can be retrieved
   * r the specified time interval elapses. If the time interval elapses, we throw a
   * ScalyrDeadlineException.
   * 
   * @param timeoutInMs Maximum time to block, in milliseconds. Null means no timeout (wait
   *     indefinitely).
   * @param undecayedTimeout If timeoutInMs is exceeded, we report undecayedTimeout as the deadline
   *     in the exception message. This is used when the caller waited for a while before invoking
   *     getWithTimeout. timeoutInMs is the *remaining* amount of time to wait, but undecayedTimeout
   *     indicates the full amount of time that was actually available for the file to be fetched.
   * 
   * @throws ScalyrDeadlineException
   */
  public FileState getWithTimeout(Long timeoutInMs, Long undecayedTimeout)
      throws ScalyrDeadlineException {
    return blockUntilValue(timeoutInMs, undecayedTimeout);
  }
  
  /**
   * Return the most recently observed state of the file's content, parsed as a JSON object.
   * <p>
   * If we have not yet managed to fetch the file, block until it can be retrieved. If the file does
   * not exist, return null. If the file is not in JSON format, throw BadConfigurationFileException.
   * 
   * @throws ScalyrDeadlineException
   */
  JSONObject getAsJson() throws BadConfigurationFileException {
    return getAsJsonWithTimeout(null, null);
  }
  
  /**
   * ONLY FOR USE BY INTERNAL SCALYR TESTS; DO NOT CALL THIS METHOD.
   */
  public JSONObject getAsJsonForTesting() throws BadConfigurationFileException {
    verifyNotClosed();
    
    return getAsJson();
  }
  
  /**
   * Like {@link #getAsJson()}, but supports deadlines, as with {@link #getWithTimeout(Long)}.
   * 
   * @throws BadConfigurationFileException
   * @throws ScalyrDeadlineException
   */
  JSONObject getAsJsonWithTimeout(Long timeoutInMs)
      throws BadConfigurationFileException, ScalyrDeadlineException {
    return getAsJsonWithTimeout(timeoutInMs, timeoutInMs);
  }
  
  /**
   * Like {@link #getAsJson()}, but supports deadlines, as with {@link #getWithTimeout(Long, Long)}.
   * 
   * @throws BadConfigurationFileException
   * @throws ScalyrDeadlineException
   */
  JSONObject getAsJsonWithTimeout(Long timeoutInMs, Long undecayedTimeout)
      throws BadConfigurationFileException, ScalyrDeadlineException {
    FileState currentState = getWithTimeout(timeoutInMs, undecayedTimeout);
    
    if (currentState.content == null)
      return null;
    
    if (cachedJsonError != null)
      throw new BadConfigurationFileException(cachedJsonError); // We clone the exception to snapshot the current stack.
    
    if (cachedJson == null) {
      Object parsed;
      try {
        if (currentState.content.length() == 0)
          parsed = new JSONObject(); // treat an empty file as an empty object, i.e. as {}
        else
          parsed = JSONParser.parse(currentState.content);
      } catch (JsonParseException ex) {
        cachedJsonError = new BadConfigurationFileException("File is not in valid JSON format (" + ex.getMessage() + ")");
        Logging.log(Severity.warning, Logging.tagKnobFileInvalid, "Error parsing [" + this + "] as JSON", cachedJsonError);
        throw cachedJsonError;
      }
      if (parsed instanceof JSONObject) {
        cachedJson = (JSONObject) parsed;
      } else {
        cachedJsonError = new BadConfigurationFileException("File is not in valid JSON format or does not have" +
        		" an object at the top level (does not begin with an open-brace)");
        Logging.log(Severity.warning, Logging.tagKnobFileInvalid, "Error parsing [" + this + "] as JSON", cachedJsonError);
        throw cachedJsonError;
      }
    }
    
    return cachedJson;
  }
  
  /**
   * Block until we've retrieved the file's initial state from the server.
   * 
   * If timeoutInMs is not null, and the file has still not been retrieved after (approximately) that
   * many milliseconds, we throw a ScalyrDeadlineException.
   * 
   * undecayedTimeout is similar to timeoutInMs, but represents the amount of time that was available
   * at the beginning of some higher-level operation. Thus, it is either equal to timeoutInMs, or has
   * been reduced by the time that elapsed before this blockUntilValue called. Used only in error reporting.
   * 
   * Caller must hold a lock on "this".
   */
  private synchronized FileState blockUntilValue(Long timeoutInMs, Long undecayedTimeout) {
    verifyNotClosed();
    
    if (fileState != null)
      return fileState;
    
    long deadlineTime = (timeoutInMs != null) ? (ScalyrUtil.currentTimeMillis() + timeoutInMs) : 0;  
    
    while (fileState == null) {
      try {
        if (timeoutInMs != null) {
          long msRemaining = deadlineTime - ScalyrUtil.currentTimeMillis();
          if (msRemaining > 0)
            this.wait(msRemaining);
          else
            throw new ScalyrDeadlineException("Initial fetch of configuration file [" +
            		pathname + "] from the server",
            		undecayedTimeout);
        } else {
          this.wait();
        }
      } catch (InterruptedException ex) {
        throw new RuntimeException(ex);
      }
    }
    
    return fileState;
  }
  
  /**
   * Register a callback to be invoked whenever the file's state changes. Each time there is a
   * change to the file, the callback will be invoked. (Note that, unlike Zookeeper "watches",
   * there is no need to re-register your callback after each change. You only need to call
   * addUpdateListener once.)
   * <p>
   * If the callback was already registered, we do nothing (it remains registered).
   */
  public synchronized void addUpdateListener(Callback<ConfigurationFile> updateListener) {
    verifyNotClosed();
    
    updateListeners.add(updateListener);
  }
  
  /**
   * De-register a callback.
   * <p>
   * If the callback was not registered, we do nothing.
   */
  public synchronized void removeUpdateListener(Callback<ConfigurationFile> updateListener) {
    updateListeners.remove(updateListener);
  }
  
  /**
   * Encapsulates information about the state of a configuration file.
   */
  public static class FileState {
    /**
     * Version number for this snapshot of the file. Deleted files have version 0. May be undefined for
     * some ConfigurationFile implementations.
     */
    protected final long version;
    
    /**
     * File content. Deleted files have null content.
     */
    public final String content;
    
    /**
     * Date/time when the file was created. Null for a deleted file.
     */
    public final Date creationDate;
    
    /**
     * Date/time when the file was last modified. Null for a deleted file.
     */
    public final Date modificationDate;
    
    public FileState(long version, String content, Date creationDate, Date modificationDate) {
      this.version = version;
      this.content = content;
      this.creationDate = creationDate;
      this.modificationDate = modificationDate;
    }
  }
}
