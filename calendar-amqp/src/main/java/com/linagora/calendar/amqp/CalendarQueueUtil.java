/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package com.linagora.calendar.amqp;

import java.util.Arrays;
import java.util.List;

import com.google.common.collect.ImmutableList;

public class CalendarQueueUtil {
    public static List<String> getAllQueueNames() {
        return ImmutableList.<String>builder()
            .addAll(Arrays.stream(EventIndexerConsumer.Queue.values())
                .map(EventIndexerConsumer.Queue::queueName)
                .collect(ImmutableList.toImmutableList()))
            .addAll(Arrays.stream(EventAlarmConsumer.Queue.values())
                .map(EventAlarmConsumer.Queue::queueName)
                .collect(ImmutableList.toImmutableList()))
            .addAll(Arrays.stream(EventResourceConsumer.Queue.values())
                .map(EventResourceConsumer.Queue::queueName)
                .collect(ImmutableList.toImmutableList()))
            .add(EventEmailConsumer.QUEUE_NAME)
            .build();
    }

    public static List<String> getAllDeadLetterQueueNames() {
        return ImmutableList.<String>builder()
            .addAll(Arrays.stream(EventIndexerConsumer.Queue.values())
                .map(EventIndexerConsumer.Queue::deadLetter)
                .collect(ImmutableList.toImmutableList()))
            .addAll(Arrays.stream(EventAlarmConsumer.Queue.values())
                .map(EventAlarmConsumer.Queue::deadLetter)
                .collect(ImmutableList.toImmutableList()))
            .addAll(Arrays.stream(EventResourceConsumer.Queue.values())
                .map(EventResourceConsumer.Queue::deadLetter)
                .collect(ImmutableList.toImmutableList()))
            .add(EventEmailConsumer.DEAD_LETTER_QUEUE)
            .build();
    }
}
