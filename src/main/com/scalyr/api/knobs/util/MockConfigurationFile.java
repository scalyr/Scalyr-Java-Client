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

package com.scalyr.api.knobs.util;

import java.util.Date;

import com.scalyr.api.internal.ScalyrUtil;
import com.scalyr.api.knobs.ConfigurationFile;

/**
 * Implementation of ConfigurationFile whose content comes from an in-memory string.
 * Useful to supply hard-coded configurations for tests of Knobs-based code.
 */
public class MockConfigurationFile extends ConfigurationFile {
  private Date creationDate = ScalyrUtil.currentDate();
  private int versionCounter = 0;

  private String content;


  public MockConfigurationFile(String pathname) {
    super(pathname);
  }

  public void setContent(String newContent) {
    content = newContent;

    setFileState(new FileState(++versionCounter, content, creationDate, ScalyrUtil.currentDate()));
  }
}
