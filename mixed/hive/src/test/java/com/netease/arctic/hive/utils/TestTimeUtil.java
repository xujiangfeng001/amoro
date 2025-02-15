/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netease.arctic.hive.utils;

import org.junit.Assert;
import org.junit.Test;

import java.time.Instant;

public class TestTimeUtil {

  @Test
  public void testTimeOverflow() {
    Instant min = Instant.parse("0000-01-01T00:00:00.000Z");
    Instant max = Instant.parse("9999-12-31T23:59:59.999Z");
    TimeUtil.microsBetween(min, max);
  }

  @Test
  public void testTimeRight() {
    Instant start = Instant.parse("2023-01-01T00:00:00.000Z");
    Instant end = Instant.parse("2023-01-01T00:00:00.123Z");
    Assert.assertEquals(123000L, TimeUtil.microsBetween(start, end));
  }
}
