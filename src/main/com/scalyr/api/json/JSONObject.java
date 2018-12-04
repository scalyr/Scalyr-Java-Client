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

package com.scalyr.api.json;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * A JSON object. Key value pairs are unordered. JSONObject supports
 * java.util.Map interface.
 */
public class JSONObject extends HashMap<String, Object> implements JSONStreamAware {
  public JSONObject() {
    super();
  }

  /**
   * Set the specified key to the specified value, and return this object.  Useful for builder-style coding, i.e.
   *
   *   JSONObject object = new JSONObject().set("foo", 1).set("bar", 2);
   */
  public JSONObject set(String key, Object value) {
    super.put(key, value);
    return this;
  }

  @Override public void writeJSONBytes(OutputStream out) throws IOException {
    out.write('{');

    boolean first = true;

    for (Map.Entry<String, Object> entry : entrySet()) {
      if (first)
        first = false;
      else
        out.write(',');

      out.write('\"');
      JSONValue.escape(String.valueOf(entry.getKey()), out);
      out.write('\"');
      out.write(':');

      if (entry.getValue() instanceof JSONStreamAware) {
        ((JSONStreamAware)entry.getValue()).writeJSONBytes(out);
      } else {
        JSONValue.writeJSONBytes(entry.getValue(), out);
      }
    }

    out.write('}');
  }

  @Override public String toString() {
    return JSONValue.toJSONString(this);
  }
}
