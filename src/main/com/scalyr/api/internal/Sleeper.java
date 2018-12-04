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

/**
 * WARNING: this class, and all classes in the .internal package, should not be
 * used by client code. (This means you.) We reserve the right to make incompatible
 * changes to the .internal package at any time.
 *
 * This class wraps Thread.sleep. We use it to allow an alternate implementation
 * to be substituted during tests.
 */
public abstract class Sleeper {
  public abstract void sleep(int intervalInMs);

  /**
   * Standard Sleeper implementation. Overridden in tests.
   */
  public static Sleeper instance;

  static {
    instance = new Sleeper() {
    @Override public void sleep(int intervalInMs) {
      try {
        Thread.sleep(intervalInMs);
      } catch (InterruptedException ex) {
        throw new RuntimeException(ex);
      }
    }
  };
  }
}
