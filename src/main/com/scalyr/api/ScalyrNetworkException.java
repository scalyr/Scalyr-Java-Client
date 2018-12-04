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

/**
 * Exception thrown for low-level errors (e.g. network errors, internal server failures)
 * while communicating with the Scalyr server. Such errors do not indicate a problem with
 * the request (such as insufficient permissions), and are typically retriable.
 */
public class ScalyrNetworkException extends ScalyrException {
  public ScalyrNetworkException(String message) {
    super(message);
  }

  public ScalyrNetworkException(String message, Throwable cause) {
    super(message, cause);
  }
}
