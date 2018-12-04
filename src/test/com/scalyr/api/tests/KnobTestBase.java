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

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.junit.Before;

import com.scalyr.api.json.JSONObject;
import com.scalyr.api.knobs.ConfigurationFileFactory;
import com.scalyr.api.knobs.KnobService;
import com.scalyr.api.tests.MockServer.ExpectedRequest;

public class KnobTestBase extends ScalyrApiTestBase {
  protected MockKnobsServer server;
  protected ConfigurationFileFactory factory;

  @Override @Before public void setup() {
    super.setup();

    server = new MockKnobsServer();
    factory = server.createFactory(null);
  }

  protected class MockKnobsServer extends KnobService {
    public final BlockingQueue<ExpectedRequest> expectedRequests = new LinkedBlockingQueue<ExpectedRequest>();

    public MockKnobsServer() {
      super("dummyToken");
      setServerAddress("dummyServerAddress");
    }

    @Override public JSONObject invokeApi(String methodName, JSONObject parameters) {
      return mockServer.invokeApi(methodName, parameters);
    }
  }
}
