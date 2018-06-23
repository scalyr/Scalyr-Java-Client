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

import java.io.*;
import java.nio.charset.Charset;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Miscellaneous utility methods.
 * 
 * WARNING: this class, and all classes in the .internal package, should not be
 * used by client code. (This means you.) We reserve the right to make incompatible
 * changes to the .internal package at any time.
 */
public class ScalyrUtil {
  public static final Charset utf8 = Charset.forName("UTF-8");
  
  /**
   * Nanoseconds per millisecond.
   */
  public static long NANOS_PER_MS = 1000000L;
  
  /**
   * Nanoseconds per second.
   */
  public static long NANOS_PER_SECOND = NANOS_PER_MS * 1000;
  
  /**
   * Throw an exception if condition is false.
   * 
   * We use this instead of the built-in Java assert() mechanism, to avoid worries about whether assertions
   * are enabled on the command line.
   */
  public static void Assert(boolean condition, String message) {
    if (!condition)
      throw new RuntimeException(message);
  }
  
  /**
   * Equivalent to a.equals(b), but handles the case where a and/or b are null.
   */
  public static boolean equals(Object a, Object b) {
    return (a == null) ? (b == null) : a.equals(b);
  }
  
  /**
   * Return all remaining content in the reader.
   */
  public static String readToEnd(Reader reader) throws IOException {
    StringBuilder sb = new StringBuilder();
    char[] buffer = new char[4096];
    while (true) {
      int count = reader.read(buffer, 0, buffer.length);
      if (count <= 0)
        break;
      sb.append(buffer, 0, count);
    }
    return sb.toString();
  }
  
  /**
   * Close the given stream, swallowing any exception.
   */
  public static void closeQuietly(InputStream stream) {
    try {
      if (stream != null) {
        stream.close();
      }
    } catch (IOException ex) {
    }
  }
  
  public static String readFileContent(File file) throws UnsupportedEncodingException, IOException {
    FileInputStream stream = new FileInputStream(file);
    try {
      return ScalyrUtil.readToEnd(new BufferedReader(new InputStreamReader(stream, "UTF-8")));
    } finally {
      ScalyrUtil.closeQuietly(stream);
    }
  }
  
  /**
   * (Near-)atomically create or overwrite the specified file with the specified content,
   * encoded as UTF-8.
   */
  public static void writeStringToFile(String text, File file) {
    try {
      // To ensure atomicity, we write to a side file and then rename into place.
      File tempFile = File.createTempFile(file.getName(), ".tmp", file.getParentFile());
      
      FileOutputStream output = new FileOutputStream(tempFile, false);
      OutputStreamWriter writer = new OutputStreamWriter(output);
      writer.write(text);
      writer.flush();
      writer.close();
      
      if (file.exists())
        file.delete();
      tempFile.renameTo(file);
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
  }
  
  /**
   * Return a string representation of this machine's IP address, or "unknown" if unable to
   * retrieve the address.
   */
  public static String getIpAddress() {
    try {
      java.net.InetAddress addr = java.net.InetAddress.getLocalHost();
      return addr.getHostAddress();
    } catch (java.net.UnknownHostException ex) {
      return "unknown";
    }
  }
  
  /**
   * Return this machine's hostname, or "unknown" if unable to retrieve the name.
   */
  public static String getHostname() {
    try {
      java.net.InetAddress addr = java.net.InetAddress.getLocalHost();
      return addr.getHostName();
    } catch (java.net.UnknownHostException ex) {
      return "unknown";
    }
  }
  
  /**
   * Executor used to invoke API operations asynchronously.
   * 
   * TODO: see http://stackoverflow.com/questions/1014528/asynchronous-http-client-for-java for better ways
   * to perform asynchronous requests. Might also think about adding batch support to the API, so that we don't
   * need multiple outstanding requests to the server.
   */
  public static ExecutorService asyncApiExecutor = createAsyncApiExecutor();

  private static ExecutorService createAsyncApiExecutor() {
    return Executors.newCachedThreadPool(new ThreadFactory(){
      private final AtomicInteger threadNumber = new AtomicInteger(1);
      @Override public Thread newThread(Runnable runnable) {
        Thread t = new Thread(runnable, "Scalyr " + threadNumber.getAndIncrement());
        t.setDaemon(true);
        return t;
      }});
  };

  /**
   * Create a new asyncApiExecutor. Used in tests.
   */
  public static void recreateAsyncApiExecutor() {
    asyncApiExecutor.shutdown();
    try {
      asyncApiExecutor.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      throw new RuntimeException(ex);
    }

    asyncApiExecutor = createAsyncApiExecutor();
  }
  
  /**
   * Most recent value passed to setCustomTimeNs, or -1 if no setCustomTimeNs is in
   * effect.
   */
  private static final AtomicLong customTimeNs = new AtomicLong(-1);
  
  /**
   * Equivalent to System.currentTimeMillis(), but the return value can be overridden for
   * testing purposes. 
   */
  public static long currentTimeMillis() {
    long custom = customTimeNs.get();
    if (custom == -1)
      return System.currentTimeMillis();
    else
      return custom / 1000000;
  }
  
  public static Date currentDate() {
    return new Date(currentTimeMillis());
  }
  
  /**
   * Specify the value to be returned by subsequent calls to currentTimeMillis.
   */
  public static void setCustomTimeNs(long value) {
    customTimeNs.set(value);
  }
  
  /**
   * Advance the current custom time by the specified delta.
   */
  public static void advanceCustomTimeMs(long delta) {
    customTimeNs.addAndGet(delta * 1000000L);
  }
  
  /**
   * Clear any outstanding setCustomTimeMs override, so that subsequent calls to
   * currentTimeMillis() will return the actual system clock.
   */
  public static void removeCustomTime() {
    customTimeNs.set(-1);
  }
  
  
  /**
   * Value which must be added to System.nanoTime() to yield # of nanoseconds since the epoch.
   */
  private static Long nanoTimeOffset = null;
  
  static {
    nanoTimeOffset = System.currentTimeMillis() * 1000000L - System.nanoTime();
  }
  
  /**
   * Equivalent to System.nanoTime(), but based off the 1/1/1970 epoch like currentTimeMillis().
   */
  public static long nanoTime() {
    long custom = customTimeNs.get();
    if (custom == -1)
      return System.nanoTime() + nanoTimeOffset;
    else
      return custom;
  }
}
