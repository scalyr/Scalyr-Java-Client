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

import com.scalyr.api.ScalyrException;
import com.scalyr.api.ScalyrNetworkException;
import com.scalyr.api.ScalyrServerException;
import com.scalyr.api.TuningConstants;
import com.scalyr.api.json.JSONObject;
import com.scalyr.api.json.JSONParser;
import com.scalyr.api.json.JSONParser.ByteScanner;
import com.scalyr.api.logs.Events;
import com.scalyr.api.logs.Severity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.Random;

/**
 * Base class for encapsulating the raw HTTP-level API to a Scalyr service.
 */
public abstract class ScalyrService {
  /**
   * If this is a positive value N, then for a random sample of one in N server invocations, we log the response time and other
   * parameters at "info" (which is typically written to a log) rather than "fine" (which is typically discarded).
   */
  public static volatile int latencyRecordingFraction = 0;

  /**
   * Server URLs, each including a trailing slash. Synchronize access.
   */
  protected String[] serverAddresses;

  /**
   * Random number generator used to randomize server choices and retry
   * intervals. Synchronize access.
   */
  protected Random random = new Random();

  /**
   * API token we pass in all requests to the server.
   */
  protected final String apiToken;

  /**
   * If true, then we set connection="close" on each request. This avoids any possibility of problems with the HTTP
   * client failing to correctly track the connection state.
   */
  public boolean closeConnections = true;

  /**
   * If true, then we explicitly disconnect after each web request. Defaults to false, but can be set to true
   * if leaving the connection open turns out to contribute to OOM problems.
   */
  public boolean explicitlyDisconnect = false;

  /**
   * Construct a ScalyrService.
   *
   * @param apiToken The API authorization token to use when communicating with the server. (If you need
   *     to use multiple api tokens, construct a separate instance for each.)
   */
  public ScalyrService(String apiToken) {
    this.apiToken = apiToken;

    setServerAddress(
        "https://api1.scalyr.com," +
        "https://api2.scalyr.com," +
        "https://api3.scalyr.com," +
        "https://api4.scalyr.com");
  }

  /**
   * Return the server URL(s) we use, as a comma-delimited list.
   */
  public synchronized String getServerAddresses() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < serverAddresses.length; i++) {
      if (i > 0)
        sb.append(",");
      sb.append(serverAddresses[i]);
    }

    return sb.toString();
  }

  /**
   * Specify the URL of the Scalyr server, e.g. https://api.scalyr.com. Trailing slash is optional.
   * <p>
   * You may specify multiple addresses, separated by commas. If multiple addresses are specified,
   * the client will choose one as it sees fit, and retry failed requests on a different
   * server.
   * <p>
   * You should only call this method if using a staging server or other non-production instance
   * of the Scalyr service. Otherwise, we automatically default to the production Scalyr service.
   * It is best to call this method at most once, before issuing any requests through this
   * service object.
   *
   * @return this ScalyrService object.
   */
  public synchronized ScalyrService setServerAddress(String value) {
    serverAddresses = value.split(",");
    for (int i = 0; i < serverAddresses.length; i++) {
      String s = serverAddresses[i];
      s = s.trim();
      if (!s.endsWith("/"))
        s += "/";

      serverAddresses[i] = s;
    }

    return this;
  }

  /**
   * Invoke methodName on a selected server, sending the specified parameters as the request
   * body. Return the (JSON-format) response.
   * <p>
   * If a "retriable" error (e.g. a network error) occurs, we retry on another server. We continue
   * retrying until a non-retriable error occurs, there are no more servers to try, or a reasonable
   * deadline (TuningConstants.MAXIMUM_RETRY_PERIOD_MS) expires.
   * <p>
   * This method should not be called directly. Instead, work through method-specific wrappers.
   *
   * @throws ScalyrException
   * @throws ScalyrNetworkException
   */
  public JSONObject invokeApi(String methodName, JSONObject parameters, boolean enableGzip) {
    return invokeApiX(methodName, parameters, enableGzip).response;
  }

  /**
   * Overloading invokeApi() with enableGzip set to default value.
   */
  public JSONObject invokeApi(String methodName, JSONObject parameters) {
    return invokeApiX(methodName, parameters, Events.ENABLE_GZIP_BY_DEFAULT).response;
  }

  /**
   * Invoke methodName on a selected server, sending the specified parameters as the request
   * body. Return the (JSON-format) response, as well as additional data about the request and
   * response.
   * <p>
   * If a "retriable" error (e.g. a network error) occurs, we retry on another server. We continue
   * retrying until a non-retriable error occurs, there are no more servers to try, or a reasonable
   * deadline (TuningConstants.MAXIMUM_RETRY_PERIOD_MS) expires.
   * <p>
   * This method should not be called directly. Instead, work through method-specific wrappers.
   *
   * @throws ScalyrException
   * @throws ScalyrNetworkException
   */
  public InvokeApiResult invokeApiX(String methodName, JSONObject parameters, boolean enableGzip) {
    return invokeApiX(methodName, parameters, new RpcOptions(), enableGzip);
  }

  /**
   * Overloading 3-parameter invokeApiX() with enableGzip set to default value.
   */
  public InvokeApiResult invokeApiX(String methodName, JSONObject parameters) {
    return invokeApiX(methodName, parameters, Events.ENABLE_GZIP_BY_DEFAULT);
  }

  /**
   * Invoke methodName on a selected server, sending the specified parameters as the request
   * body. Return the (JSON-format) response, as well as additional data about the request and
   * response.
   * <p>
   * If a "retriable" error (e.g. a network error) occurs, we retry on another server. We continue
   * retrying until a non-retriable error occurs, there are no more servers to try, or a reasonable
   * deadline (TuningConstants.MAXIMUM_RETRY_PERIOD_MS) expires.
   * <p>
   * This method should not be called directly. Instead, work through method-specific wrappers.
   *
   * @throws ScalyrException
   * @throws ScalyrNetworkException
   */
  public InvokeApiResult invokeApiX(String methodName, JSONObject parameters, RpcOptions options, boolean enableGzip) {
    // Produce a shuffled copy of the server addresses, so that load is distributed
    // across the servers.
    int N = serverAddresses.length;
    String[] shuffled = new String[N];
    System.arraycopy(serverAddresses, 0, shuffled, 0, N);

    for (int i = 0; i < N - 1; i++) {
      int j = i + random.nextInt(N - i);
      String temp = shuffled[i];
      shuffled[i] = shuffled[j];
      shuffled[j] = temp;
    }

    // Try the operation on each server in turn.
    long startTimeMs = ScalyrUtil.currentTimeMillis();
    int serverIndex = 0;
    while (true) {
      String serverAddress = shuffled[serverIndex];
      long requestStartTimeMs = ScalyrUtil.currentTimeMillis();
      try {
        return invokeApiOnServer(serverAddress, methodName, parameters, options, enableGzip);
      } catch (ScalyrNetworkException ex) {
        // Fall into the loop and retry the operation on the next server.
        // If there are no more servers, or our deadline has expired, then
        // rethrow the exception.
        serverIndex++;

        long totalElapsedMs = ScalyrUtil.currentTimeMillis() - startTimeMs;

        if (serverIndex >= N) {
          long requestElapsedMs = ScalyrUtil.currentTimeMillis() - requestStartTimeMs;
          Logging.log(Severity.warning, Logging.tagServerError,
              "invokeApi: " + methodName + " failed on " + serverAddress
              + " (after " + requestElapsedMs + " milliseconds); no more servers to try, so giving up", ex);
          throw ex;
        } else if (totalElapsedMs >= options.maxRetryIntervalMs) {
          Logging.log(Severity.warning, Logging.tagServerError,
              "invokeApi: " + methodName + " failed on " + serverAddress
              + "; maximum retry period of " + TuningConstants.MAXIMUM_RETRY_PERIOD_MS
              + " ms exceeded, so giving up", ex);
          throw ex;
        }

        Logging.log(Severity.warning, Logging.tagServerError,
            "invokeApi: " + methodName + " failed on " + serverAddress + "; will retry", ex);
      }
    }
  }

  /**
   * Overloading 4-parameter invokeApiX() with enableGzip set to default value.
   */
  public InvokeApiResult invokeApiX(String methodName, JSONObject parameters, RpcOptions options) {
    return invokeApiX(methodName, parameters, options, Events.ENABLE_GZIP_BY_DEFAULT);
  }

  /**
   * Options used when sending a request to a server.
   */
  public static class RpcOptions {
    /**
     * If not empty/null, then we add a "?" and this string to the URL. Should be of the form "name=value" or "name=value&amp;name=value".
     */
    public String queryParameters;

    /**
     * HTTP connextion timeout (see HttpURLConnection.setConnectTimeout).
     */
    public int connectionTimeoutMs = TuningConstants.HTTP_CONNECT_TIMEOUT_MS;

    /**
     * HTTP read timeout (see HttpURLConnection.setReadTimeout).
     */
    public int readTimeoutMs = TuningConstants.MAXIMUM_RETRY_PERIOD_MS;

    /**
     * Time span during which API operations may be retried (e.g. in response to a network
     * error). Once this many milliseconds have elapsed from the initial API invocation, we
     * no longer issue retries.
     */
    public int maxRetryIntervalMs = TuningConstants.MAXIMUM_RETRY_PERIOD_MS;
  }

  /**
   * Values returned by invokeApiX: the RPC response, plus diagnostic data.
   */
  public static class InvokeApiResult {
    /**
     * Parsed response from the server.
     */
    public JSONObject response;

    /**
     * Length of the serialized JSON request, in bytes. May not reflect compression.
     */
    public int requestLength;

    /**
     * Length of the serialized JSON response, in bytes. May not reflect compression.
     */
    public int responseLength;

    /**
     * The latency of the request in milliseconds.
     */
    public int latencyMs;
  }

  /**
   * Invoke serverAddress/methodName on the server, sending the specified parameters as the request
   * body. Return the (JSON-format) response.
   *
   * @throws ScalyrException
   * @throws ScalyrNetworkException
   */
  protected InvokeApiResult invokeApiOnServer(String serverAddress, String methodName, JSONObject parameters,
      RpcOptions options, boolean enableGzip) {
    AbstractHttpClient httpClient = null;

    long timeBeforeCreatingClient = System.nanoTime();
    long timeBeforeRequestingResponse = -1;
    long timeAfterReceivingResponse = -1;

    try {
      CountingOutputStream countingStream = new CountingOutputStream();
      parameters.writeJSONBytes(countingStream);
      int requestLength = countingStream.bytesWritten;

      // Send the request.
      long startTimeMs = ScalyrUtil.currentTimeMillis();
      String urlString = serverAddress + methodName;
      if (options.queryParameters != null && !"".equals(options.queryParameters))
        urlString += "?" + options.queryParameters;

      URL url = new URL(urlString);

      if (TuningConstants.serverInvocationCounter != null)
        TuningConstants.serverInvocationCounter.increment();

      try {
        if (TuningConstants.useApacheHttpClientForEventUploader != null && TuningConstants.useApacheHttpClientForEventUploader.get()) {
          ByteArrayOutputStream requestBuffer = new ByteArrayOutputStream();
          parameters.writeJSONBytes(requestBuffer);

          byte[] byteArray = requestBuffer.toByteArray();
          httpClient = new ApacheHttpClient(url, requestLength, closeConnections, options, byteArray, byteArray.length,
                                            "application/json", enableGzip);
        } else {
          httpClient = new JavaNetHttpClient(url, requestLength, closeConnections, options, "application/json", enableGzip);

          OutputStream output = httpClient.getOutputStream();
          parameters.writeJSONBytes(output);
          output.flush();
          output.close();
        }

        // Retrieve the response.
        timeBeforeRequestingResponse = System.nanoTime();
        int responseCode = httpClient.getResponseCode();
        timeAfterReceivingResponse = System.nanoTime();

        byte[] rawResponse;

        try {
          InputStream input = httpClient.getInputStream();
          rawResponse = readEntireStream(input);
        } finally {
          httpClient.finishedReadingResponse();
        }

        // Log a random sample of server response times.
        Severity severity = Severity.fine;
        if (latencyRecordingFraction == 1) {
          severity = Severity.info;
        } else if (latencyRecordingFraction > 0) {
          synchronized (random) {
            if (random.nextInt(latencyRecordingFraction) == 0) {
              severity = Severity.info;
            }
          }
        }

        int runtimeMs = (int) (ScalyrUtil.currentTimeMillis() - startTimeMs);
        Logging.log(severity, Logging.tagServerCommunication,
            serverAddress + "/" + methodName + ": "
            + runtimeMs + " ms, "
            + requestLength + " bytes sent, "
            + rawResponse.length + " bytes received, "
            + "response status " + responseCode
            );

        if (responseCode != 200) {
          // TODO: log StringUtil.noisyTruncate(response.responseBody.trim(), 1000));
          // also do this in the "Malformed response" case
          throw new ScalyrNetworkException("Scalyr server returned error code " + responseCode);
        }

        ByteScanner responseScanner = new ByteScanner(rawResponse);
        Object responseObj = new JSONParser(responseScanner).parseValue();
        if (responseObj instanceof JSONObject) {
          JSONObject responseJson = (JSONObject)responseObj;
          Object status = responseJson.get("status");
          Logging.log(Severity.finer, Logging.tagServerCommunication,
              "Response status [" + (status == null ? "(none)" : status) + "]"
              );

          throwIfErrorStatus(responseJson);

          InvokeApiResult result = new InvokeApiResult();
          result.requestLength = requestLength;
          result.responseLength = responseScanner.getPos();
          result.response = responseJson;
          result.latencyMs = runtimeMs;
          return result;
        } else {
          throw new ScalyrException("Malformed response from Scalyr server");
        }

      } finally {
        if (TuningConstants.serverInvocationTimeCounterSecs != null) {
          TuningConstants.serverInvocationTimeCounterSecs.increment((ScalyrUtil.currentTimeMillis() - startTimeMs) / 1000.0);
        }
      }
    } catch (Exception ex) {
      String timingDetails = "";
      if (timeAfterReceivingResponse != -1) {
        timingDetails = "creating client -> request response -> receive response -> now: "
            + (timeBeforeRequestingResponse - timeBeforeCreatingClient) / 1000000 + ", "
            + (timeAfterReceivingResponse - timeBeforeRequestingResponse) / 1000000 + ", "
            + (System.nanoTime() - timeAfterReceivingResponse) / 1000000 + " ms";
      } else if (timeBeforeRequestingResponse != -1) {
        timingDetails = "creating client -> request response -> now: "
            + (timeBeforeRequestingResponse - timeBeforeCreatingClient) / 1000000 + ", "
            + (System.nanoTime() - timeBeforeRequestingResponse) / 1000000 + " ms";
      } else {
        timingDetails = "creating client -> now: "
            + (System.nanoTime() - timeBeforeCreatingClient) / 1000000 + " ms";
      }

      if (ex instanceof SocketTimeoutException) {
        throw new ScalyrNetworkException("Timeout while communicating with Scalyr server (" + timingDetails + ")", ex);
      } else {
        throw new ScalyrNetworkException("Error while communicating with Scalyr server (" + timingDetails + ")", ex);
      }
    } finally {
      if (explicitlyDisconnect && httpClient != null) {
        httpClient.disconnect();
      }
    }
  }

  /**
   * Overloading invokeApiOnServer() with enableGzip set to default value.
   */
  protected InvokeApiResult invokeApiOnServer(String serverAddress, String methodName, JSONObject parameters, RpcOptions options) {
    return invokeApiOnServer(serverAddress, methodName, parameters, options, Events.ENABLE_GZIP_BY_DEFAULT);
  }

  public static void throwIfErrorStatus(JSONObject responseJson) {
    Object status = responseJson.get("status");
    Object statusCode = responseJson.get("__status");
    if (statusCode instanceof Integer) {
      //noinspection SillyAssignment
      statusCode = (Long) (long) (int) (Integer) statusCode;
    }
    if (statusCode instanceof Long) {
      long statusCode_ = (Long) statusCode;
      if (statusCode_ != 200) {
        throw new ScalyrServerException("Error response from Scalyr server: status " + statusCode_
            + " (" + status + "), message [" + responseJson.get("message") + "]");
      }
    }

    if (status instanceof String && ((String)status).startsWith("error")) {
      throw new ScalyrServerException("Error response from Scalyr server: status [" + status + "], message ["
          + responseJson.get("message") + "]");
    }
  }

  private byte[] readEntireStream(InputStream input) throws IOException {
    ByteArrayOutputStream accumulator = new ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    while (true) {
      int count = input.read(buffer, 0, buffer.length);
      if (count <= 0)
        break;
      accumulator.write(buffer, 0, count);
    }
    return accumulator.toByteArray();
  }
}
