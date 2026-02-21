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

import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.crypto.CryptoProtocolVersion;
import org.apache.hadoop.fs.CreateFlag;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.protocol.ClientProtocol;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.io.EnumSetWritable;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.EnumSet;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

public class NNPerfTester {
    private static final Logger logger = LoggerFactory.getLogger(NNPerfTester.class);

    public static final int BLOCK_SIZE = 1048576; // NN-throughput uses 16
    private static final AtomicLong            operationCount = new AtomicLong(0);
    private static final AtomicLong            readIdx        = new AtomicLong(0);
    private static final AtomicLong            writeIdx       = new AtomicLong(0);
    private static final AtomicLong            appendIdx      = new AtomicLong(0);
    private static final AtomicLong            deleteIdx      = new AtomicLong(0);
    private static final AtomicLong            renameIdx      = new AtomicLong(0);
    private static final AtomicLong            statusIdx      = new AtomicLong(0);
    private static final String                hostIdFile     = ".hostId";

    private static       Configuration         config;
    private static       ClientProtocol        clientProto;
    private static       FileContext           fc;

    private final        int                   readOpCount;
    private final        int                   writeOpCount;
    private final        int                   appendOpCount;
    private final        int                   renameOpCount;
    private final        int                   deleteOpCount;
    private final        int                   fileStatusOpCount;
    private final        int                   listFilesOpCount;
    private final        int                   mkdirOpCount;
    private final        int                   totalThreads;
    private final        int                   iterations;
    private final        String                principal;
    private final        String                keytab;
    private final        String                hadoopConfDir;
    private final        String                hdfsRwPathPrefix;
    private final        String                hdfsFileSuffix;
    private final        String                hdfsRenameTempDir;
    private final        String                jmeterInstallDir;
    private final        int                   filesProvisioned;
    private final        int                   remoteHostsConfigured;
    private final        String                hostNameShort;

    private              DistributedFileSystem dfs;
    private              int                   hostId         = -1;

    NNPerfTester(boolean generateTestPlan) {
        Properties props = readProperties();
        this.principal             = props.getProperty(Constants.KERBEROS_PRINCIPAL);
        this.keytab                = props.getProperty(Constants.KERBEROS_KEYTAB);
        this.hadoopConfDir         = props.getProperty(Constants.LOCAL_CONF_DIR);
        this.hdfsRwPathPrefix      = props.getProperty(Constants.HDFS_RW_PATH_PREFIX);
        this.hdfsFileSuffix        = props.getProperty(Constants.HDFS_FILE_SUFFIX);
        this.hdfsRenameTempDir     = props.getProperty(Constants.HDFS_RENAME_TEMP_DIR);
        this.jmeterInstallDir      = props.getProperty(Constants.JMETER_INSTALL_DIR);
        this.filesProvisioned      = Integer.parseInt(props.getProperty(Constants.HDFS_FILES_PROVISIONED));
        this.iterations            = Integer.parseInt(props.getProperty(Constants.HDFS_ITERATIONS));
        this.totalThreads          = Integer.parseInt(props.getProperty(Constants.TEST_PLAN_THREADS));
        this.remoteHostsConfigured = props.getProperty(Constants.JMETER_REMOTE_HOSTS).split(",").length;
        this.readOpCount           = Integer.parseInt(props.getProperty(Constants.HDFS_OP_COUNT_READ));
        this.writeOpCount          = Integer.parseInt(props.getProperty(Constants.HDFS_OP_COUNT_WRITE));
        this.deleteOpCount         = Integer.parseInt(props.getProperty(Constants.HDFS_OP_COUNT_DELETE));
        this.appendOpCount         = Integer.parseInt(props.getProperty(Constants.HDFS_OP_COUNT_APPEND));
        this.renameOpCount         = Integer.parseInt(props.getProperty(Constants.HDFS_OP_COUNT_RENAME));
        this.fileStatusOpCount     = Integer.parseInt(props.getProperty(Constants.HDFS_OP_COUNT_FILE_STATUS));
        this.listFilesOpCount      = Integer.parseInt(props.getProperty(Constants.HDFS_OP_COUNT_LIST_FILES));
        this.mkdirOpCount          = Integer.parseInt(props.getProperty(Constants.HDFS_OP_COUNT_MKDIR));
        this.hostNameShort         = getHostNameShort();

        if (generateTestPlan) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(hostIdFile))) {
            this.hostId = Integer.parseInt(reader.readLine());
        } catch (IOException e) {
            logger.error(String.format("Failed to read Host Id from %s", hostIdFile), e);
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) throws IOException {
        NNPerfTester nnPerfTester = new NNPerfTester(false);
        nnPerfTester.setHDFSConfig(nnPerfTester.getConfig());
        nnPerfTester.runOp(Constants.HDFS_READ, 0, nnPerfTester.getFilesProvisioned(), Constants.HDFS_READ);
    }

    public int getHostId() {
        return hostId;
    }

    public int getRemoteHostsConfigured() {
        return remoteHostsConfigured;
    }

    public int getTotalThreads() {
        return totalThreads;
    }

    public int getIterations() {
        return iterations;
    }

    public int getFilesProvisioned() {
        return filesProvisioned;
    }

    public String getHdfsRwPathPrefix() {
        return hdfsRwPathPrefix;
    }

    public String getHdfsFileSuffix() {
        return hdfsFileSuffix;
    }

    public String getHdfsRenameTempDir() {
        return hdfsRenameTempDir;
    }

    public String getJmeterInstallDir() {
        return jmeterInstallDir;
    }

    public AtomicLong getOperationCount() {
        return operationCount;
    }

    public int getReadOpCount() {
        return readOpCount;
    }

    public int getWriteOpCount() {
        return writeOpCount;
    }

    public int getDeleteOpCount() {
        return deleteOpCount;
    }

    public int getRenameOpCount() {
        return renameOpCount;
    }

    public int getAppendOpCount() {
        return appendOpCount;
    }

    public int getFileStatusOpCount() {
        return fileStatusOpCount;
    }

    public int getListFilesOpCount() {
        return listFilesOpCount;
    }

    public int getMkdirOpCount() {
        return mkdirOpCount;
    }

    public void resetWriteIdx() {
        writeIdx.set(0);
    }

    String getHostNameShort() {
        InetAddress inetAddress;
        try {
            inetAddress = InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
        String   hostname = inetAddress.getHostName();
        String[] parts    = hostname.split("\\.");
        return parts[0];
    }

    void setHDFSConfig(Configuration conf) throws IOException {
        config = conf;
        // We do not need many handlers, since each thread simulates a handler by calling name-node methods directly
        config.setInt(DFSConfigKeys.DFS_DATANODE_HANDLER_COUNT_KEY, 1);
        // Turn off minimum block size verification
        config.setInt(DFSConfigKeys.DFS_NAMENODE_MIN_BLOCK_SIZE_KEY, 0);
        // set exclude file
        config.set(DFSConfigKeys.DFS_HOSTS_EXCLUDE, "${hadoop.tmp.dir}/dfs/hosts/exclude");
        File excludeFile = new File(config.get(DFSConfigKeys.DFS_HOSTS_EXCLUDE, "exclude"));
        if (!excludeFile.exists()) {
            if (!excludeFile.getParentFile().exists() && !excludeFile.getParentFile().mkdirs()) {
                throw new IOException("HDFSFileReader: cannot mkdir " + excludeFile);
            }
        }
        new FileOutputStream(excludeFile).close();
        // set include file
        config.set(DFSConfigKeys.DFS_HOSTS, "${hadoop.tmp.dir}/dfs/hosts/include");
        File includeFile = new File(config.get(DFSConfigKeys.DFS_HOSTS, "include"));
        new FileOutputStream(includeFile).close();

        dfs         = (DistributedFileSystem) FileSystem.get(config);
        clientProto = dfs.getClient().getNamenode();
        fc          = FileContext.getFileContext(config);
    }

    Properties readProperties() {
        Properties properties = new Properties();
        try (FileInputStream fileInputStream = new FileInputStream(Constants.PROPERTIES_FILE)) {
            properties.load(fileInputStream);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return properties;
    }

    Configuration getConfig() {
        Configuration conf = new Configuration();

        // Set Hadoop configurations
        conf.addResource(new Path(this.hadoopConfDir + "/core-site.xml"));
        conf.addResource(new Path(this.hadoopConfDir + "/hdfs-site.xml"));

        // kerberos confs
        conf.set("hadoop.security.authentication", "kerberos");
        conf.set("hadoop.security.authentication.kerberos.principal", this.principal);
        conf.set("hadoop.security.authentication.kerberos.keytab", this.keytab);

        UserGroupInformation.setConfiguration(conf);
        try {
            UserGroupInformation.loginUserFromKeytab(this.principal, this.keytab);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return conf;
    }

    long executeRead(String fileName) {
        long    start     = Time.now();
        boolean isSuccess = false;
        try {
            clientProto.getBlockLocations(fileName, 0L, 16);
            isSuccess = true;
        } catch (IOException e) {
            logger.error(String.format("Unable to read: %s", fileName), e);
        }
        long end = Time.now();
        if (isSuccess) {
            operationCount.incrementAndGet();
        }
        return end - start;
    }

    long executeDelete(String fileName) {
        long    start     = Time.now();
        boolean isSuccess = false;
        try {
            clientProto.delete(fileName, false);
            isSuccess = true;
        } catch (IOException e) {
            logger.error(String.format("Unable to delete: %s", fileName), e);
        }
        long end = Time.now();
        if (isSuccess) {
            operationCount.incrementAndGet();
        }
        return end - start;
    }

    long executeWrite(String fileName, String clientName) { // create
        short   replication = (short) config.getInt(DFSConfigKeys.DFS_REPLICATION_KEY, 3);
        long    end         = 0;
        long    start       = Time.now();
        boolean isSuccess   = false;
        try {
            //clientProto.setSafeMode(HdfsConstants.SafeModeAction.SAFEMODE_LEAVE, false);
            // change dfs block-size to a lower value in HDFS config
            clientProto.create(fileName, FsPermission.getDefault(), clientName, new EnumSetWritable<CreateFlag>(EnumSet.of(CreateFlag.CREATE, CreateFlag.OVERWRITE)), true, replication, BLOCK_SIZE, CryptoProtocolVersion.supported(), null, null);
            end = Time.now();

            clientProto.complete(fileName, clientName, null, HdfsConstants.GRANDFATHER_INODE_ID);
            isSuccess = true;
        } catch (IOException e) {
            logger.error(String.format("Unable to create/complete: %s", fileName), e);
        }
        if (isSuccess) {
            operationCount.incrementAndGet();
        }
        return end - start;
    }

    long executeRename(String sourceFileName, String destFileName) {
        long    start     = Time.now();
        boolean isSuccess = false;
        try {
            clientProto.rename(sourceFileName, destFileName);
            isSuccess = true;
        } catch (IOException e) {
            logger.error(String.format("Unable to rename: %s", sourceFileName), e);
        }
        long end = Time.now();
        if (isSuccess) {
            operationCount.incrementAndGet();
        }
        return end - start;
    }

    long executeAppend(String fileName, String stringToAppend) {
        long    start     = Time.now();
        boolean isSuccess = false;
        try {
            Path               path         = new Path(dfs.getUri() + fileName);
            FSDataOutputStream outputStream = dfs.append(path);
            outputStream.writeBytes(stringToAppend);
            outputStream.close();
            isSuccess = true;
        } catch (IOException e) {
            logger.error(String.format("Unable to append: %s", fileName), e);
        }
        long end = Time.now();
        if (isSuccess) {
            operationCount.incrementAndGet();
        }
        return end - start;
    }

    long executeFileStatus(String fileName) {
        long    start     = Time.now();
        boolean isSuccess = false;
        try {
            clientProto.getFileInfo(fileName);
            isSuccess = true;
        } catch (IOException e) {
            logger.error(String.format("Unable to get fileStatus on: %s", fileName), e);
        }
        long end = Time.now();
        if (isSuccess) {
            operationCount.incrementAndGet();
        }
        return end - start;
    }

    long executeListFiles(Path path) {
        long    start     = Time.now();
        boolean isSuccess = false;
        try {
            fc.util().listFiles(path, false);
            isSuccess = true;
        } catch (IOException e) {
            logger.error(String.format("Unable to execute listStatus on: %s", path.toString()), e);
        }
        long end = Time.now();
        if (isSuccess) {
            operationCount.incrementAndGet();
        }
        return end - start;
    }

    long executeMkdir(Path path) {
        long    start     = Time.now();
        boolean isSuccess = false;
        try {
            fc.mkdir(path, FsPermission.getDefault(), true);
            isSuccess = true;
        } catch (IOException e) {
            logger.error(String.format("Unable to create dir : %s", path.toString()), e);
        }
        long end = Time.now();
        if (isSuccess) {
            operationCount.incrementAndGet();
        }
        return end - start;
    }

    long runOp(String opType, int regionStart, int hostRange, String subDirName) {
        logger.debug(String.format("--> runOp(opType = %s)", opType));

        int    currentThreadId = (int) Thread.currentThread().getId();
        String clientName      = String.format("%s-client-%s", hostNameShort, currentThreadId);
        String hdfsPathPrefix  = String.format("%s/%s", getHdfsRwPathPrefix(), opType);
        long   timeTaken       = 0;
        long   index           = -1;

        if (subDirName != null) {
            hdfsPathPrefix = String.format("%s/%s", getHdfsRwPathPrefix(), subDirName);
        }
        if (StringUtils.equals(opType, Constants.HDFS_WRITE)) {
            index = regionStart + (writeIdx.getAndIncrement() % hostRange);
            String hdfsPath = String.format("%s/%s%s", hdfsPathPrefix, index, getHdfsFileSuffix());
            timeTaken = executeWrite(hdfsPath, clientName);
        } else if (StringUtils.equals(opType, Constants.HDFS_APPEND)) {
            index = regionStart + (appendIdx.getAndIncrement() % hostRange);
            String hdfsPath = String.format("%s/%s%s", hdfsPathPrefix, index, getHdfsFileSuffix());
            timeTaken = executeAppend(hdfsPath, Constants.STRING_TO_APPEND);
        } else if (StringUtils.equals(opType, Constants.HDFS_RENAME)) {
            index = regionStart + (renameIdx.getAndIncrement() % hostRange);
            String hdfsPath    = String.format("%s/%s%s", hdfsPathPrefix, index, getHdfsFileSuffix());
            String renamedPath = String.format("%s/%s%s", getHdfsRenameTempDir(), index, getHdfsFileSuffix());
            long   timeTaken1  = executeRename(hdfsPath, renamedPath);
            long   timeTaken2  = executeRename(renamedPath, hdfsPath);
            timeTaken = (timeTaken1 + timeTaken2) / 2;
        } else if (StringUtils.equals(opType, Constants.HDFS_DELETE)) {
            index = regionStart + deleteIdx.getAndIncrement();
            String hdfsPath = String.format("%s/%s%s", hdfsPathPrefix, index, getHdfsFileSuffix());
            timeTaken = executeDelete(hdfsPath);
        } else if (StringUtils.equals(opType, Constants.HDFS_READ)) {
            index = regionStart + (readIdx.getAndIncrement() % hostRange);
            String hdfsPath = String.format("%s/%s%s", hdfsPathPrefix, index, getHdfsFileSuffix());
            timeTaken = executeRead(hdfsPath);
        } else if (StringUtils.equals(opType, Constants.HDFS_FILE_STATUS)) {
            index = regionStart + (statusIdx.getAndIncrement() % hostRange);
            String hdfsPath = String.format("%s/%s%s", hdfsPathPrefix, index, getHdfsFileSuffix());
            timeTaken = executeFileStatus(hdfsPath);
        } else if (StringUtils.equals(opType, Constants.HDFS_LIST_FILES)) {
            String[] opTypes = new String[] {
                    Constants.HDFS_READ,
                    Constants.HDFS_WRITE,
                    Constants.HDFS_APPEND,
                    Constants.HDFS_RENAME,
                    Constants.HDFS_DELETE
            };
            Random rand = new Random();
            Path   path = new Path(String.format("%s/%s", getHdfsRwPathPrefix(), opTypes[rand.nextInt(opTypes.length)]));
            timeTaken = executeListFiles(path);
        } else if (StringUtils.equals(opType, Constants.HDFS_MKDIR)) {
            Random rand = new Random();
            Path   path = new Path(String.format("%s/%s/%s", getHdfsRwPathPrefix(), "new_dirs", rand.nextInt(10000)));
            timeTaken = executeMkdir(path);
        }

        logger.debug(String.format("Time taken to runOps(%s): %s ms", opType, timeTaken));
        logger.debug(String.format("<-- runOps(opType = %s)", opType));
        return index;
    }
}
