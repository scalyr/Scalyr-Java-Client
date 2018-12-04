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

/**
 * Exception thrown when a configuration file contains invalid content (e.g. is expected to contain
 * JSON data but is not a valid JSON file).
 */
public class BadConfigurationFileException extends ScalyrException {
  public BadConfigurationFileException(String message) {
    super(message);
  }

  public BadConfigurationFileException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Construct a clone of the specified exception. Used to generate an exception object with
   * a fresh stack trace, but the same message and cause.
   */
  public BadConfigurationFileException(BadConfigurationFileException valueToClone) {
    super(valueToClone.getMessage(), valueToClone.getCause());
  }
}
