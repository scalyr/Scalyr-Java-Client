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

import com.scalyr.api.internal.ScalyrUtil;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * A JSON array. JSONObject supports java.util.List interface.
 */
public class JSONArray extends ArrayList<Object> implements JSONStreamAware {
  public JSONArray(Object ... values) {
    for (Object value : values)
      add(value);
  }

  @Override public void writeJSONBytes(OutputStream out) throws IOException {
    out.write('[');

    boolean first = true;

    for (Object value : this) {
      if (first)
        first = false;
      else
        out.write(',');

      if (value == null) {
        out.write("null".getBytes(ScalyrUtil.utf8));
        continue;
      }

      if (value instanceof JSONStreamAware) {
        ((JSONStreamAware)value).writeJSONBytes(out);
      } else {
        JSONValue.writeJSONBytes(value, out);
      }
    }

    out.write(']');
  }

  @Override public String toString() {
    return JSONValue.toJSONString(this);
  }

}
