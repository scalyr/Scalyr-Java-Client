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

import com.scalyr.api.ScalyrException;
import com.scalyr.api.ScalyrNetworkException;
import com.scalyr.api.internal.ScalyrService;
import com.scalyr.api.json.JSONObject;

import java.io.File;

/**
 * Encapsulates the raw HTTP-level API to the Knobs service.
 */
public class KnobService extends ScalyrService {
  /**
   * Construct a KnobService.
   * 
   * @param apiToken The API authorization token to use when communicating with the server. (If you need
   *     to use multiple api tokens, construct a separate KnobService instance for each.)
   */
  public KnobService(String apiToken) {
    super(apiToken);
  }

  @Override public synchronized KnobService setServerAddress(String value) {
    return (KnobService) super.setServerAddress(value);
  }
  
  /**
   * Return a ConfigurationFileFactory that retrieves files via this KnobService instance.
   * 
   * @param cacheDir If not null, then we store a copy of each retrieved file in this directory.
   *     On subsequent calls to createFactory, we use the stored files to initialize our state until
   *     each file can be re-fetched from the server. This prevents configuration file reads from
   *     stalling. If this directory does not exist, we create it.
   */
  public ConfigurationFileFactory createFactory(final File cacheDir) {
    if (cacheDir != null && !cacheDir.exists())
      cacheDir.mkdirs();
    
    return new ConfigurationFileFactory(){
      @Override protected ConfigurationFile createFileReference(String filePath) {
        return new HostedConfigurationFile(KnobService.this, filePath, cacheDir);
      }};
  }
  
  /**
   * Retrieve a configuration file.
   * 
   * @param path The file path, e.g. "/params.txt". Must begin with a slash.
   * @param expectedVersion Should normally be null. If not null, equal to the file's current version
   *     number, then the content field will be omitted from the response. Saves bandwidth when
   *     you are merely checking to see whether a file has changed since a previous known version.
   * @param obsolete_waitTime Obsolete parameter (ignored).
   * 
   * @return The response from the server. See <a href='https://www.scalyr.com/help/api'>scalyr.com/help/api</a>.
   * 
   * @throws ScalyrException
   * @throws ScalyrNetworkException
   */
  public String getFile(String path, Long expectedVersion, Integer obsolete_waitTime)
      throws ScalyrException, ScalyrNetworkException {
    JSONObject parameters = new JSONObject();
    parameters.put("token", apiToken);
    parameters.put("path", path);
    if (expectedVersion != null)
      parameters.put("expectedVersion", expectedVersion);
    
    JSONObject parsed = invokeApi("getFile", parameters);
    if (parsed != null)
      return parsed.toString();
    else
      return null;
  }
  
  /**
   * Create, update, or delete a configuration file.
   * 
   * @param path The file path, e.g. "/params.txt". Must begin with a slash.
   * @param expectedVersion Should normally be null. If not null, and not equal to the file's current version,
   *     then the file will not be modified. Enables atomic read/modify/write operations.
   * @param content New content for the file. Pass null if passing deleteFile = true.
   * @param deleteFile True to delete the file, false to create/update it.
   * 
   * @return The response from the server. See <a href='https://www.scalyr.com/help/api'>scalyr.com/help/api</a>.
   * 
   * @throws ScalyrException
   * @throws ScalyrNetworkException
   */
  public JSONObject putFile(String path, Long expectedVersion, String content, boolean deleteFile)
      throws ScalyrException, ScalyrNetworkException {
    JSONObject parameters = new JSONObject();
    parameters.put("token", apiToken);
    parameters.put("path", path);
    if (expectedVersion != null)
      parameters.put("expectedVersion", expectedVersion);
    
    if (deleteFile)
      parameters.put("deleteFile", true);
    else
      parameters.put("content", content);
    
    return invokeApi("putFile", parameters);
  }
  
  /**
   * Retrieve a list of all configuration files.
   * 
   * @return The response from the server. See <a href='https://www.scalyr.com/help/api'>scalyr.com/help/api</a>.
   * 
   * @throws ScalyrException
   * @throws ScalyrNetworkException
   * 
   * Note that paths are complete (absolute) pathnames, beginning with a slash, and are sorted
   * lexicographically.
   */
  public JSONObject listFiles() 
      throws ScalyrException, ScalyrNetworkException {
    JSONObject parameters = new JSONObject();
    parameters.put("token", apiToken);
    
    return invokeApi("listFiles", parameters);
  }
}
