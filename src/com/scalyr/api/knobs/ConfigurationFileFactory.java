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

import java.util.HashMap;
import java.util.Map;

/**
 * Factory which encapsulates a collection of configuration files, accessible by pathname.
 */
public abstract class ConfigurationFileFactory {
  /**
   * Maps pathname to ConfigurationFile instance. Used to avoid creating multiple ConfigurationFiles
   * for the same file.
   *
   * Access synchronized on "this".
   *
   * TODO: use a finalizer to remove entries from fileMap when they're no longer referenced.
   * For this to work, we'll need to use weak references in fileMap.
   */
  private final Map<String, ConfigurationFile> fileMap = new HashMap<String, ConfigurationFile>();

  /**
   * Return an object representing the specified pathname in this collection.
   * <p>
   * Note that we always return an object, even if no file currently exists at the
   * specified pathname.
   */
  public synchronized ConfigurationFile getFile(String pathname) {
    // If we already have a ConfigurationFile object for this path, return it. Otherwise,
    // create one.
    ConfigurationFile file = fileMap.get(pathname);
    if (file == null) {
      file = createFileReference(pathname);
      fileMap.put(pathname, file);
    }

    return file;
  }

  /**
   * Create a ConfigurationFile object for the file at the given path in this repository.
   */
  protected abstract ConfigurationFile createFileReference(String filePath);
}
