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

import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.util.Time;
import org.apache.jmeter.protocol.java.sampler.AbstractJavaSamplerClient;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.samplers.SampleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Serializable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class JmeterSampler extends AbstractJavaSamplerClient implements Serializable {
    private static final Logger logger = LoggerFactory.getLogger(JmeterSampler.class);

    private static final NNPerfTester  nnPerfTester   = new NNPerfTester(false);
    private static final AtomicInteger activeThreads  = new AtomicInteger(0);
    private static final AtomicInteger fileEndIndex   = new AtomicInteger(0);
    private static final AtomicInteger fileStartIndex = new AtomicInteger(99999999);
    private static final AtomicBoolean printFlag      = new AtomicBoolean(true);
    private static final AtomicBoolean writeFlag      = new AtomicBoolean(true);
    private static final String        indexFile      = "index.txt";
    private static final DistGenerator generator      = new DistGenerator(
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
            new int[] {
                    nnPerfTester.getReadOpCount(),
                    nnPerfTester.getWriteOpCount(),
                    nnPerfTester.getDeleteOpCount(),
                    nnPerfTester.getAppendOpCount(),
                    nnPerfTester.getRenameOpCount(),
                    nnPerfTester.getFileStatusOpCount(),
                    nnPerfTester.getListFilesOpCount(),
                    nnPerfTester.getMkdirOpCount()
            });
    private static long expectedFileDeletes;
    private static int  regionStart;

    public static void main(String[] args) {
        if (args.length > 0 && StringUtils.equalsIgnoreCase(args[0], "recover")) {
            createFiles(nnPerfTester.getTotalThreads(), Constants.HDFS_DELETE);
        } else if (args.length > 0 && StringUtils.equalsIgnoreCase(args[0], "create")) {
            createFiles(nnPerfTester.getTotalThreads(), Constants.HDFS_READ);
            nnPerfTester.resetWriteIdx();
            createFiles(nnPerfTester.getTotalThreads(), Constants.HDFS_WRITE);
            nnPerfTester.resetWriteIdx();
            createFiles(nnPerfTester.getTotalThreads(), Constants.HDFS_APPEND);
            nnPerfTester.resetWriteIdx();
            createFiles(nnPerfTester.getTotalThreads(), Constants.HDFS_RENAME);
            nnPerfTester.resetWriteIdx();
            createFiles(nnPerfTester.getTotalThreads(), Constants.HDFS_FILE_STATUS);
            nnPerfTester.resetWriteIdx();
            createFiles(nnPerfTester.getTotalThreads(), Constants.HDFS_DELETE);
            nnPerfTester.resetWriteIdx();
        } else {
            nnPerfTester.runOp(Constants.HDFS_READ, 0, nnPerfTester.getFilesProvisioned(), null);
        }
    }

    /**
     * Host Range: Number of files allocated to a particular host.
     */
    public int getHostRange() {
        return nnPerfTester.getFilesProvisioned() / nnPerfTester.getRemoteHostsConfigured();
    }

    public void printToConsole(String message, String data) {
        short  length   = 26;
        String fMessage = String.format("%-" + length + "s", message);
        System.out.println(fMessage + ": " + data);
    }

    @Override
    public void setupTest(JavaSamplerContext context) {
        boolean val = printFlag.getAndSet(false);
        if (val) {
            long totalFileOps    = 0;
            int  hostRange       = getHostRange();
            long numOfOperations = (long) hostRange * nnPerfTester.getIterations();
            long allOpCount      = generator.getDistSize();

            System.out.println("--------------------------  RUN CONFIGS --------------------------");
            printToConsole("Host Id", String.valueOf(nnPerfTester.getHostId()));
            printToConsole("HDFS RW Path Prefix", String.valueOf(nnPerfTester.getHdfsRwPathPrefix()));
            printToConsole("Total Files Provisioned", String.valueOf(nnPerfTester.getFilesProvisioned()));
            printToConsole("Files Provisioned for host", String.valueOf(getHostRange()));
            printToConsole("Total Threads", String.valueOf(nnPerfTester.getTotalThreads()));
            printToConsole("Iterations", String.valueOf(nnPerfTester.getIterations()));
            if (nnPerfTester.getReadOpCount() > 0) {
                long readOps = (numOfOperations * nnPerfTester.getReadOpCount()) / allOpCount;
                totalFileOps += readOps;
                printToConsole("Expected File Reads", String.valueOf(readOps));
            }
            if (nnPerfTester.getWriteOpCount() > 0) {
                long writeOps = (numOfOperations * nnPerfTester.getWriteOpCount()) / allOpCount;
                totalFileOps += writeOps;
                printToConsole("Expected File Writes", String.valueOf(writeOps));
            }
            if (nnPerfTester.getAppendOpCount() > 0) {
                long appendOps = (numOfOperations * nnPerfTester.getAppendOpCount()) / allOpCount;
                totalFileOps += appendOps;
                printToConsole("Expected File Appends", String.valueOf(appendOps));
            }
            if (nnPerfTester.getDeleteOpCount() > 0) {
                expectedFileDeletes = (numOfOperations * nnPerfTester.getDeleteOpCount()) / allOpCount;
                totalFileOps += expectedFileDeletes;
                printToConsole("Expected File Deletes", String.valueOf(expectedFileDeletes));
            }
            if (nnPerfTester.getRenameOpCount() > 0) {
                long renameOps = (numOfOperations * nnPerfTester.getRenameOpCount()) / allOpCount;
                totalFileOps += renameOps;
                printToConsole("Expected File Renames", String.valueOf(renameOps));
            }
            if (nnPerfTester.getFileStatusOpCount() > 0) {
                long statusOps = (numOfOperations * nnPerfTester.getFileStatusOpCount()) / allOpCount;
                totalFileOps += statusOps;
                printToConsole("Expected File Status calls", String.valueOf(statusOps));
            }
            if (nnPerfTester.getListFilesOpCount() > 0) {
                long listFileOps = (numOfOperations * nnPerfTester.getListFilesOpCount()) / allOpCount;
                totalFileOps += listFileOps;
                printToConsole("Expected List File calls", String.valueOf(listFileOps));
            }
            if (nnPerfTester.getMkdirOpCount() > 0) {
                long mkdirOps = (numOfOperations * nnPerfTester.getMkdirOpCount()) / allOpCount;
                totalFileOps += mkdirOps;
                printToConsole("Expected mkdir calls", String.valueOf(mkdirOps));
            }
            printToConsole("Total Expected File Ops", String.valueOf(totalFileOps));
            System.out.println("------------------------------------------------------------------");
        }
    }

    @Override
    public void teardownTest(JavaSamplerContext context) {
        boolean val = writeFlag.getAndSet(false);
        if (val && (nnPerfTester.getDeleteOpCount() > 0)) {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(nnPerfTester.getJmeterInstallDir() + "/" + indexFile))) {
                writer.write(String.valueOf(regionStart));
                writer.newLine();
                writer.write(String.valueOf(expectedFileDeletes));
                logger.info(String.format("Deleted file index range written to %s successfully.", indexFile));
            } catch (IOException e) {
                logger.error(String.format("Failed to write deleted file index range to file %s", indexFile), e);
            }
        }
        printFlag.getAndSet(true);
    }

    @Override
    public SampleResult runTest(JavaSamplerContext context) {
        SampleResult result = new SampleResult();
        result.sampleStart(); // Start measuring the time

        activeThreads.incrementAndGet();
        int regionRange  = getHostRange();
        int iterations   = nnPerfTester.getIterations();
        int totalThreads = nnPerfTester.getTotalThreads();
        regionStart = nnPerfTester.getHostId() * regionRange;
        boolean lastRename = true;
        try {
            for (int i = 0; i < getHostRange() / totalThreads; i++) {
                for (int j = 0; j < iterations; j++) {
                    String opType = generator.getNext();
                    if (StringUtils.equalsIgnoreCase(opType, Constants.HDFS_RENAME)) {
                        if (lastRename) {
                            nnPerfTester.runOp(opType, regionStart, regionRange, null);
                            lastRename = false;
                        } else {
                            // skip rename this time since last runOp counted rename twice
                            lastRename = true;
                            // next rename will be executed
                        }
                    } else {
                        nnPerfTester.runOp(opType, regionStart, regionRange, null);
                    }
                }
            }
            result.setSuccessful(true);
            result.setResponseCodeOK(); // Set response code to indicate success
        } catch (Exception e) {
            result.setSuccessful(false);
            result.setResponseMessage("Error executing operation: " + e.getMessage());
            result.setResponseCode("500"); // Set response code to indicate failure
        } finally {
            result.sampleEnd(); // Stop measuring the time
            result.setResponseMessage("Operated on HDFS files!!");
        }

        int remainingThreads = activeThreads.decrementAndGet();
        if (remainingThreads == 0) {
            System.out.println("Total file operations performed: " + nnPerfTester.getOperationCount());
        }
        return result;
    }

    /**
     * To create new files / re-create deleted files in the last run.
     * Multithreaded impl for faster recovery.
     */
    private static void createFiles(int threads, String subDir) {
        long startTime = Time.now();
        try (BufferedReader reader = new BufferedReader(new FileReader(indexFile))) {
            fileStartIndex.set(Integer.parseInt(reader.readLine()));
            fileEndIndex.set(Integer.parseInt(reader.readLine()));
        } catch (IOException e) {
            logger.error(String.format("Failed to read file indices from the file %s", indexFile), e);
        }

        ExecutorService executorService = Executors.newFixedThreadPool(threads);
        int             range           = (fileEndIndex.get() - fileStartIndex.get()) / threads;
        int             hostRange       = nnPerfTester.getFilesProvisioned() / nnPerfTester.getRemoteHostsConfigured();

        CreateTask createTask = () -> {
            long index = nnPerfTester.runOp(Constants.HDFS_WRITE, fileStartIndex.get(), hostRange, subDir);
            System.out.println(String.format("Thread %s re-created file with index %s", Thread.currentThread().getId(), index));
        };
        for (int i = 0; i < range; i++) {
            for (int j = 0; j < threads; j++) {
                executorService.execute(createTask::runTask);
            }
        }
        executorService.shutdown();
        try {
            executorService.awaitTermination(300, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        System.out.println("All threads have completed.");
        long endTime = Time.now();
        logger.info(String.format("Time taken to create files: %d ms", endTime - startTime));
    }

    static {
        try {
            nnPerfTester.setHDFSConfig(nnPerfTester.getConfig());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
