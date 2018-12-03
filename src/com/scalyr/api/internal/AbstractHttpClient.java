package com.scalyr.api.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Abstraction for an HTTP client. Currently supports only simple POST requests.
 */
public abstract class AbstractHttpClient {
  /**
   * Get the stream to which the request body is written.
   */
  public abstract OutputStream getOutputStream() throws IOException;

  /**
   * Return the HTTP status code of the response.
   */
  public abstract int getResponseCode() throws IOException;

  /**
   * Return the content type of the response body, or empty/null if none.
   */
  public abstract String getResponseContentType();

  /**
   * Return the Content-Encoding header of the response, or empty/null if none.
   */
  public abstract String getResponseEncoding();

  /**
   * Get the stream from which the response body can be read. You should eventually call finishedReadingResponse().
   */
  public abstract InputStream getInputStream() throws IOException;

  /**
   * Call this method when you are finished reading the response body. It frees some underlying resources.
   */
  public abstract void finishedReadingResponse() throws IOException;

  public abstract void disconnect();
}
