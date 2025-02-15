/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netease.arctic.ams.api.metrics;

import com.netease.arctic.ams.api.ActivePlugin;

/**
 * This is an interface defining a reporter, which users can implement to notify metrics to a
 * monitoring system.
 */
public interface MetricsEmitter extends ActivePlugin {

  /**
   * emit metrics to the monitoring system
   *
   * @param metrics {@link MetricsContent} to emit.
   */
  void emit(MetricsContent<?> metrics);

  /**
   * determine whether the emitter accepts the metrics according to {@link MetricsContent#type()}
   * and {@link MetricsContent#name()}
   *
   * @param metrics metrics data
   * @return true if the type and name is accepted by the emitter
   */
  boolean accept(MetricsContent<?> metrics);
}
