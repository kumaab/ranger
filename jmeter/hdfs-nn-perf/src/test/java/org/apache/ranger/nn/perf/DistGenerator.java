/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ranger.nn.perf;

import java.util.concurrent.atomic.AtomicInteger;

public class DistGenerator {
    private final String[]      dist;
    private final AtomicInteger nextIdx = new AtomicInteger();

    public DistGenerator(String[] values, int[] valueCounts) {
        int distSize = 0;
        for (int valueCount : valueCounts) {
            distSize += valueCount;
        }
        this.dist = new String[distSize];
        for (int idxValue = 0; idxValue < values.length; idxValue++) {
            String value       = values[idxValue];
            int    valueCount  = valueCounts[idxValue];
            double minInterval = distSize / (double) valueCount;
            for (int i = 0; i < valueCount; i++) {
                insert(value, (int) (i * minInterval));
            }
        }
    }

    public static void main(String[] args) {
        DistGenerator generator = new DistGenerator(
                new String[] {
                        Constants.HDFS_READ,
                        Constants.HDFS_WRITE,
                        Constants.HDFS_DELETE,
                        Constants.HDFS_APPEND,
                        Constants.HDFS_RENAME,
                        Constants.HDFS_FILE_STATUS,
                        Constants.HDFS_LIST_FILES,
                        Constants.HDFS_MKDIR
                },
                new int[] {920, 20, 10, 10, 20, 10, 8, 2});
        int count = generator.getDistSize() * 2;
        for (int i = 0; i < count; i++) {
            System.out.println(generator.getNext());
        }
    }

    public int getDistSize() {
        return dist.length;
    }

    public String getNext()  {
        return dist[nextIdx.getAndIncrement() % dist.length];
    }

    private void insert(String value, int minIdx) {
        while (dist[minIdx] != null) {
            minIdx++;
        }
        dist[minIdx] = value;
    }
}
