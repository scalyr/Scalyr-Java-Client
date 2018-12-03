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

import com.scalyr.api.Converter;
import com.scalyr.api.ScalyrDeadlineException;
import com.scalyr.api.TuningConstants;
import com.scalyr.api.internal.Logging;
import com.scalyr.api.internal.ScalyrUtil;
import com.scalyr.api.json.JSONObject;
import com.scalyr.api.logs.Severity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Encapsulates a specific entry in a JSON-format configuration file.
 * <p>
 * It is generally best to use a type-specific subclass of Knob, such as
 * Knob.Integer or Knob.String, rather than casting the result of
 * Knob.get() yourself. This is because Java casting does not know how to
 * convert between numeric types (e.g. Double -&gt; Integer), and so simple casting
 * is liable to throw ClassCastException.
 */
public class Knob {
  /**
   * Files in which we look for the value. We use the first file that defines our key.
   *
   * If useDefaultFiles is true, then this list should be overwritten with defaultFiles before
   * use.
   */
  private ConfigurationFile[] files;

  /**
   * If true, then our files list needs to be replaced with defaultFiles. We do this lazily on first access,
   * so that Knobs can be constructed before setDefaultFiles is invoked.
   */
  private boolean useDefaultFiles;

  /**
   * The key we look for in each file. If null, then we always use our default value.
   */
  private final java.lang.String key;

  /**
   * Value used if no file defines the key.
   */
  private final Object defaultValue;

  /**
   * False until we first retrieve a value from the configuration file. Once this is true, it will never
   * be false.
   */
  private volatile boolean hasValue;

  /**
   * True if we are sure that our value is up to date with respect to the underlying configuration files.
   * Always false if hasValue is false.
   */
  private volatile boolean valueUpToDate;

  /**
   * The most recently observed value. Undefined if hasValue is false.
   */
  private volatile Object value;

  /**
   * The number of times we've had to fetch our value from the configuration file. Used to decide when to
   * create a file listener and proactively track the value.
   */
  private int uncachedFetchCount;

  /**
   * Callback used to listen for changes in the underlying file, or null if we are not currently
   * listening. We listen if updateListeners is nonempty, or if this knob has been fetched enough
   * times that it's worth caching.
   */
  private Consumer<ConfigurationFile> fileListener;

  /**
   * All callbacks which have been registered to be notified when our value changes.
   */
  private Set<Consumer<Knob>> updateListeners = new HashSet<>();

  /**
   * List of files to be used if no files were explicitly specified. Null until initialized by
   * a call to setDefaultFiles.
   */
  private static AtomicReference<ConfigurationFile[]> defaultFiles = new AtomicReference<ConfigurationFile[]>(null);

  /**
   * Specify a default list of configuration files. This will be used for any Knob in which no file list was specified.
   * Existing Knobs are not affected by changes to the default file list.
   */
  public static void setDefaultFiles(ConfigurationFile[] files) {
    defaultFiles.set(files);
  }

  /**
   * @param key The key to look for (a fieldname of the top-level JSON object in the file), or null to always use defaultValue.
   * @param defaultValue Value to return from {@link #get()} if the file does not exist or does not
   *     contain the key.
   * @param files The files in which we look for the value. We use the first file that
   *     defines the specified key. If no files are specified, we use defaultFiles
   */
  public Knob(java.lang.String key, Object defaultValue, ConfigurationFile ... files) {
    if (files.length > 0) {
      this.files = files;
    } else {
      useDefaultFiles = true;
    }
    this.key = key;
    this.defaultValue = defaultValue;
  }

  /**
   * If our files list has not yet been initialized, initialize it with the default.
   */
  private synchronized void prepareFilesList() {
    if (useDefaultFiles) {
      files = defaultFiles.get();
      if (files == null)
        throw new RuntimeException("Must call setDefaultFiles before using a Knob with no ConfigurationFiles");
      useDefaultFiles = false;
    }
  }

  /**
   * Return a value from the first configuration file which contains the specified key.
   * <p>
   * Ignore any files which do not exist. If none of the files contain the key, or the key is null, return defaultValue.
   * If any file has not yet been retrieved from the server, we block until it can be retrieved.
   *
   * @param valueKey A key into the top-level object in that file, or null to force the default value.
   * @param defaultValue Value to return if the file does not exist or does not contain the key.
   * @param files The files in which we search for the value.
   */
  public static Object get(java.lang.String valueKey, Object defaultValue, ConfigurationFile ... files) {
    return new Knob(valueKey, defaultValue, files).get();
  }

  /**
   * Like {@link #get(java.lang.String, Object, ConfigurationFile[])}, but converts the value to an Integer.
   */
  public static java.lang.Integer getInteger(java.lang.String valueKey, java.lang.Integer defaultValue, ConfigurationFile ... files) {
    return Converter.toInteger(get(valueKey, defaultValue, files));
  }

  /**
   * Like {@link #get(java.lang.String, Object, ConfigurationFile[])}, but converts the value to an Long.
   */
  public static java.lang.Long getLong(java.lang.String valueKey, java.lang.Long defaultValue, ConfigurationFile ... files) {
    return Converter.toLong(get(valueKey, defaultValue, files));
  }

  /**
   * Like {@link #get(java.lang.String, Object, ConfigurationFile[])}, but converts the value to a Double.
   */
  public static java.lang.Double getDouble(java.lang.String valueKey, java.lang.Double defaultValue, ConfigurationFile ... files) {
    return Converter.toDouble(get(valueKey, defaultValue, files));
  }

  /**
   * Like {@link #get(java.lang.String, Object, ConfigurationFile[])}, but converts the value to a Boolean.
   */
  public static java.lang.Boolean getBoolean(java.lang.String valueKey, java.lang.Boolean defaultValue, ConfigurationFile ... files) {
    return Converter.toBoolean(get(valueKey, defaultValue, files));
  }

  /**
   * Like {@link #get(java.lang.String, Object, ConfigurationFile[])}, but converts the value to a String.
   */
  public static java.lang.String getString(java.lang.String valueKey, java.lang.String defaultValue, ConfigurationFile ... files) {
    return Converter.toString(get(valueKey, defaultValue, files));
  }

  /**
   * Return the value at the specified key in our file.
   *
   * If the file does not exist or does not contain the key (or the key is null), return our default value.
   * If the file has not yet been retrieved from the server, we block until it can be retrieved.
   */
  public Object get() {
    return getWithTimeout(null);
  }

  /**
   * Like get(), but if the file has not yet been retrieved from the server, and the specified time
   * interval elapses before the file is retrieved from the server, throw a ScalyrDeadlineException.
   *
   * @throws ScalyrDeadlineException
   */
  public Object getWithTimeout(java.lang.Long timeoutInMs) throws ScalyrDeadlineException {
    return getWithTimeout(timeoutInMs, false);
  }

  /**
   * Like get(), but if the file has not yet been retrieved from the server, and the specified time
   * interval elapses before the file is retrieved from the server, throw a ScalyrDeadlineException.
   *
   * @param timeoutInMs Maximum amount of time to wait for the initial file retrieval. Null means
   *     to wait as long as needed.
   * @param bypassCache If true, then we always examine the configuration file(s), rather than relying on
   *     our cached value for the knob.
   *
   * @throws ScalyrDeadlineException
   */
  public Object getWithTimeout(java.lang.Long timeoutInMs, boolean bypassCache) throws ScalyrDeadlineException {
    if (!bypassCache && valueUpToDate)
      return value;

    Object newValue = defaultValue;
    boolean ensuredFileListener = false;
    if (key != null) {
      synchronized (this) {
        uncachedFetchCount++;
        if (uncachedFetchCount >= TuningConstants.KNOB_CACHE_THRESHOLD) {
          // Ensure that we have a fileListener, so that we can update our value if the configuration
          // file(s) change. We don't do this unless the knob is fetched repeatedly, because the fileListener
          // will prevent this Knob object from ever being garbage collected.
          ensureFileListener();
          ensuredFileListener = true;
        }
      }

      long entryTime = (timeoutInMs != null) ? ScalyrUtil.currentTimeMillis() : 0;

      prepareFilesList();
      for (ConfigurationFile file : files) {
        JSONObject parsedFile;
        try {
          if (timeoutInMs != null) {
            long elapsed = Math.max(ScalyrUtil.currentTimeMillis() - entryTime, 0);
            parsedFile = file.getAsJsonWithTimeout(timeoutInMs - elapsed, timeoutInMs);
          } else {
            parsedFile = file.getAsJson();
          }
        } catch (BadConfigurationFileException ex) {
          parsedFile = null;

          Logging.log(Severity.info, Logging.tagKnobFileInvalid,
              "Knob: ignoring file [" + file + "]: it does not contain valid JSON");
        }

        if (parsedFile != null && parsedFile.containsKey(key)) {
          newValue = parsedFile.get(key);
          break;
        }
      }
    }

    synchronized (this) {
      Object oldValue = value;
      boolean hadValue = hasValue;

      value = newValue;
      hasValue = true;
      if (ensuredFileListener)
        valueUpToDate = true;

      if (!hadValue || !ScalyrUtil.equals(value, oldValue)) {
        List<Consumer<Knob>> listenerSnapshot = new ArrayList<>(updateListeners);
        for (Consumer<Knob> updateListener : listenerSnapshot) {
          updateListener.accept(this);
        }
      }

      return newValue;
    }
  }

  /**
   * Register a callback to be invoked whenever our value changes.
   */
  public synchronized Knob addUpdateListener(Consumer<Knob> updateListener) {
    if (updateListeners.size() == 0) {
      ensureFileListener();
    }
    updateListeners.add(updateListener);

    return this;
  }

  /**
   * If we don't yet have a fileListener, then add one.
   */
  private synchronized void ensureFileListener() {
    if (fileListener == null) {
      fileListener = updatedFile -> {
        if (allFilesHaveValues())
          getWithTimeout(null, true);
      };
      prepareFilesList();
      for (ConfigurationFile file : files)
        file.addUpdateListener(fileListener);
    }
  }

  protected synchronized boolean allFilesHaveValues() {
    prepareFilesList();
    for (ConfigurationFile file : files)
      if (!file.hasState())
        return false;

    return true;
  }

  /**
   * De-register a callback. If the callback was not registered, we do nothing.
   */
  public synchronized Knob removeUpdateListener(Consumer<Knob> updateListener) {
    updateListeners.remove(updateListener);
    return this;
  }


  /**
   * No-op developer convenience/hygiene method: when defining a new Knob that
   * we ought to cleanup at some point, add this method to the Knob declaration:
   *
   * ```
   * final Knob.Boolean useOldImplementation = new Knob.Boolean("useOldImplementation", false).expireHint("12/15/2017");
   * ```
   *
   * @param dateStr after which we may want to pull this knob.  Not currently parsed.
   * @return self for chaining
   */
  public Knob expireHint(java.lang.String dateStr) {
    return this;
  }

  /**
   * Subclass of Knob which is specialized for Integer values, with or without SI.
   */
  public static class Integer extends Knob {
    public Integer(java.lang.String valueKey, java.lang.Integer defaultValue, ConfigurationFile ... files) {
      super(valueKey, defaultValue, files);
    }

    @Override public java.lang.Integer get() {
      return convertWithSI(super.get());
    }

    @Override public java.lang.Integer getWithTimeout(java.lang.Long timeoutInMs) throws ScalyrDeadlineException {
      return convertWithSI(super.getWithTimeout(timeoutInMs));
    }

    @Override public Integer expireHint(java.lang.String dateStr) {
      return this;
    }

    private java.lang.Integer convertWithSI(Object obj) {
      try {
        return Converter.toInteger(obj);
      } catch (RuntimeException ex) {
        return Converter.parseNumberWithSI(obj).intValue();
      }
    }
  }

  /**
   * Subclass of Knob which is specialized for Long values, with or without SI.
   */
  public static class Long extends Knob {
    public Long(java.lang.String valueKey, java.lang.Long defaultValue, ConfigurationFile ... files) {
      super(valueKey, defaultValue, files);
    }

    @Override public java.lang.Long get() {
      return convertWithSI(super.get());
    }

    @Override public java.lang.Long getWithTimeout(java.lang.Long timeoutInMs) throws ScalyrDeadlineException {
      return convertWithSI(super.getWithTimeout(timeoutInMs));
    }

    @Override public Long expireHint(java.lang.String dateStr) {
      return this;
    }

    private java.lang.Long convertWithSI(Object obj) {
      try {
        return Converter.toLong(obj);
      } catch (RuntimeException ex) {
        return Converter.parseNumberWithSI(obj);
      }
    }
  }

  /**
   * Subclass of Knob which is specialized for Double values.
   */
  public static class Double extends Knob {
    public Double(java.lang.String valueKey, java.lang.Double defaultValue, ConfigurationFile ... files) {
      super(valueKey, defaultValue, files);
    }

    @Override public java.lang.Double get() {
      return Converter.toDouble(super.get());
    }

    @Override public java.lang.Double getWithTimeout(java.lang.Long timeoutInMs) throws ScalyrDeadlineException {
      return Converter.toDouble(super.getWithTimeout(timeoutInMs));
    }

    @Override public Double expireHint(java.lang.String dateStr) {
      return this;
    }
  }

  /**
   * Subclass of Knob which is specialized for Boolean values.
   */
  public static class Boolean extends Knob {
    public Boolean(java.lang.String valueKey, java.lang.Boolean defaultValue, ConfigurationFile ... files) {
      super(valueKey, defaultValue, files);
    }

    @Override public java.lang.Boolean get() {
      return Converter.toBoolean(super.get());
    }

    @Override public java.lang.Boolean getWithTimeout(java.lang.Long timeoutInMs) throws ScalyrDeadlineException {
      return Converter.toBoolean(super.getWithTimeout(timeoutInMs));
    }

    @Override public Boolean expireHint(java.lang.String dateStr) {
      return this;
    }
  }

  /**
   * Subclass of Knob which is specialized for String values.
   */
  public static class String extends Knob {
    public String(java.lang.String valueKey, java.lang.String defaultValue, ConfigurationFile ... files) {
      super(valueKey, defaultValue, files);
    }

    @Override public java.lang.String get() {
      return Converter.toString(super.get());
    }

    @Override public java.lang.String getWithTimeout(java.lang.Long timeoutInMs) throws ScalyrDeadlineException {
      return Converter.toString(super.getWithTimeout(timeoutInMs));
    }

    @Override public String expireHint(java.lang.String dateStr) {
      return this;
    }
  }
}
