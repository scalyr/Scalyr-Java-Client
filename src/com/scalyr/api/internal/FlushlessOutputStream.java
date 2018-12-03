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

import java.io.IOException;
import java.io.OutputStream;

/**
 * Wraps an OutputStream and swallows flush() and close() calls. Used when we create an
 * OutputStreamWriter and want to flush it without flushing the underlying stream.
 */
public class FlushlessOutputStream extends OutputStream {
  private final OutputStream innerStream;

  public FlushlessOutputStream(OutputStream innerStream) {
    this.innerStream = innerStream;
  }

  @Override public void write (int b) throws IOException {
    innerStream.write(b);
  }

  @Override public void write (byte[] b) throws IOException, NullPointerException {
    innerStream.write(b);
  }

  @Override public void write (byte[] b, int off, int len)
      throws IOException, NullPointerException, IndexOutOfBoundsException {
    innerStream.write(b, off, len);
  }
}
