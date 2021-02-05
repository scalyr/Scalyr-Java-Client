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

package com.scalyr.api.logs;

import com.scalyr.api.ScalyrException;
import com.scalyr.api.ScalyrNetworkException;
import com.scalyr.api.internal.*;
import com.scalyr.api.json.JSONArray;
import com.scalyr.api.json.JSONObject;
import com.scalyr.api.json.JSONStreamAware;

/**
 * Encapsulates the raw HTTP-level API to the Logs service.
 */
public class LogService extends ScalyrService {
  /**
   * Construct a LogService.
   *
   * @param apiToken The API authorization token to use when communicating with the server. (If you need
   *     to use multiple api tokens, construct a separate LogService instance for each.)
   */
  public LogService(String apiToken) {
    super(apiToken);

    setServerAddress("https://log.scalyr.com");
  }

  @Override public synchronized LogService setServerAddress(String value) {
    return (LogService) super.setServerAddress(value);
  }

  /**
   * Upload a batch of events to the Scalyr Logs service. See the
   * <a href="https://www.scalyr.com/help/api">HTTP API documentation</a> for a detailed description
   * of each parameter.
   *
   * @param sessionId ID of this process instance.
   * @param sessionInfo Attributes to associate with this session. Should be remain constant for
   *     all calls to uploadEvents with a given session ID.
   * @param events The events to upload (a JSON array).
   * @param threadInfos Optional; contains information for the threads referenced in the events array.
   * @param enableGzip Optional; enable gzip compression on the upload.
   *
   * @return The JSON-formatted response from the server. See <a href='https://www.scalyr.com/help/api'>scalyr.com/help/api</a>.
   *
   * @throws ScalyrException
   * @throws ScalyrNetworkException
   */
  public JSONObject uploadEvents(String sessionId, JSONObject sessionInfo,
      JSONStreamAware events, JSONArray threadInfos, boolean enableGzip)
      throws ScalyrException, ScalyrNetworkException {
    JSONObject parameters = buildParams(sessionId, sessionInfo, events, threadInfos);
    return invokeApi("addEvents", parameters, enableGzip);
  }

  public JSONObject buildParams(String sessionId, JSONObject sessionInfo, JSONStreamAware events, JSONArray threadInfos) {
    JSONObject parameters = new JSONObject();

    parameters.put("clientVersion", 1);
    parameters.put("token", apiToken);
    parameters.put("session", sessionId);
    if (sessionInfo != null)
      parameters.put("sessionInfo", sessionInfo);
    parameters.put("events", events);
    if (threadInfos != null && threadInfos.size() > 0)
      parameters.put("threads", threadInfos);
    return parameters;
  }

  public JSONObject uploadEvents(ApacheProcessContext apc, ApacheThreadContext atc, JSONObject parameters) throws ScalyrException {
    return invokeApiX("addEvents", parameters, new RpcOptions(), Events.ENABLE_GZIP_BY_DEFAULT,
        (url, requestLength, closeConnections, params, options, contentEncoding) ->
            ScalyrService.postJsonWithApache(apc.client, atc.context, url, requestLength, closeConnections, params, options, contentEncoding)).response;
  }

  /**
   * uploadEvents() wrapper with enableGzip set to default value.
   */
  public JSONObject uploadEvents(String sessionId, JSONObject sessionInfo,
                                 JSONStreamAware events, JSONArray threadInfos) {
    return uploadEvents(sessionId, sessionInfo, events, threadInfos, Events.ENABLE_GZIP_BY_DEFAULT);
  }

  public static final int SPAN_TYPE_LEAF  = 0;
  public static final int SPAN_TYPE_START = 1;
  public static final int SPAN_TYPE_END   = 2;
}
