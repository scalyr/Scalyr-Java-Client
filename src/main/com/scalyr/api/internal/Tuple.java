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

import java.util.ArrayList;

/**
 * Encapsulates a tuple of values, and implements equals() and hashCode().
 * Used for hashtable keys.
 *
 * WARNING: this class, and all classes in the .internal package, should not be
 * used by client code. (This means you.) We reserve the right to make incompatible
 * changes to the .internal package at any time.
 */
public class Tuple extends ArrayList<Object> {
  public Tuple(Object ... values) {
    super(values.length);

    for (Object value : values)
      add(value);
  }

  @Override public boolean equals(Object o) {
    if (!(o instanceof Tuple))
      return false;

    Tuple tuple = (Tuple) o;
    if (tuple.size() != size())
      return false;

    for (int i = 0; i < size(); i++)
      if (!ScalyrUtil.equals(get(i), tuple.get(i)))
        return false;

    return true;
  }

  @Override public int hashCode() {
    int result = 0;
    for (Object value : this)
      result = result * 9973 + hashOrNull(value);
    return result;
  }

  private static int hashOrNull(Object o) {
    return (o != null) ? o.hashCode() : 0;
  }
}
