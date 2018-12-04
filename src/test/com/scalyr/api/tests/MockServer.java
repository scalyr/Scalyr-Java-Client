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

package com.scalyr.api.tests;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import com.scalyr.api.json.JSONObject;
import com.scalyr.api.json.JSONParser;

public class MockServer {
  public final BlockingQueue<ExpectedRequest> expectedRequests = new LinkedBlockingQueue<ExpectedRequest>();

  public JSONObject invokeApi(String methodName, JSONObject parameters) {
    try {
      ExpectedRequest expected = expectedRequests.take();

      assertEquals(expected.expectedMethodName, methodName);
      TestUtils.assertEquivalent(expected.expectedParameters, parameters);

      if (expected.response != null)
        return expected.response;
      else
        throw new RuntimeException("simulated error connecting to the server");
    } catch (InterruptedException ex) {
      throw new RuntimeException(ex);
    }
  }

  public static class ExpectedRequest {
    final String expectedMethodName;
    final JSONObject expectedParameters;
    final JSONObject response;

    public ExpectedRequest(String expectedMethodName, String expectedParameters, String response) {
      this.expectedMethodName = expectedMethodName;
      this.expectedParameters = (JSONObject) JSONParser.parse(expectedParameters.replace('\'', '"'));
      if (response != null)
        this.response = (JSONObject) JSONParser.parse(response.replace('\'', '"'));
      else
        this.response = null;
    }
  }
}
