package org.apache.ranger.audit.utils;

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

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonPathCapabilities;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.StreamCapabilities;
import org.apache.ranger.audit.provider.MiscUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.util.Date;
import java.util.Map;
import java.util.Properties;

/**
 * This is Abstract class to have common properties of Ranger Audit HDFS Destination Writer.
 */
public abstract class AbstractRangerAuditWriter implements RangerAuditWriter {
    private static final Logger logger = LoggerFactory.getLogger(AbstractRangerAuditWriter.class);

    public static final String PROP_FILESYSTEM_DIR              = "dir";
    public static final String PROP_FILESYSTEM_SUBDIR           = "subdir";
    public static final String PROP_FILESYSTEM_FILE_NAME_FORMAT = "filename.format";
    public static final String PROP_FILESYSTEM_FILE_ROLLOVER    = "file.rollover.sec";
    public static final String PROP_FILESYSTEM_ROLLOVER_PERIOD  = "file.rollover.period";
    public static final String PROP_FILESYSTEM_FILE_EXTENSION   = ".log";
    public static final String PROP_IS_APPEND_ENABLED           = "file.append.enabled";

    public Configuration       conf;
    public FileSystem          fileSystem;
    public Map<String, String> auditConfigs;
    public Path                auditPath;
    public RollingTimeUtil     rollingTimeUtil;
    public String              auditProviderName;
    public String              fullPath;
    public String              parentFolder;
    public String              currentFileName;
    public String              logFileNameFormat;
    public String              logFolder;
    public String              fileExtension;
    public String              rolloverPeriod;
    public String              fileSystemScheme;
    public Date                nextRollOverTime;
    public int                 fileRolloverSec = 24 * 60 * 60; // In seconds
    public boolean             rollOverByDuration;

    public volatile PrintWriter         logWriter;
    public volatile FSDataOutputStream  ostream;   // output stream wrapped in logWriter

    protected boolean reUseLastLogFile;
    private   boolean isHFlushCapableStream;

    @Override
    public void init(Properties props, String propPrefix, String auditProviderName, Map<String, String> auditConfigs) {
        // Initialize properties for this class
        // Initial folder and file properties
        logger.info("==> AbstractRangerAuditWriter.init()");

        this.auditProviderName = auditProviderName;
        this.auditConfigs      = auditConfigs;

        init(props, propPrefix);

        logger.info("<== AbstractRangerAuditWriter.init()");
    }

    @Override
    public void flush() {
        logger.debug("==> AbstractRangerAuditWriter.flush() {}", fileSystemScheme);

        if (ostream != null) {
            try {
                synchronized (this) {
                    if (ostream != null) {
                        // 1) PrinterWriter does not have bufferring of its own so
                        // we need to flush its underlying stream
                        // 2) HDFS flush() does not really flush all the way to disk.
                        if (isHFlushCapableStream) {
                            //Checking HFLUSH capability of the stream because of HADOOP-13327.
                            //For S3 filesysttem, hflush throws UnsupportedOperationException and hence we call flush.
                            ostream.hflush();
                        } else {
                            ostream.flush();
                        }
                    }

                    logger.debug("Flush {} audit logs completed.....", fileSystemScheme);
                }
            } catch (IOException e) {
                logger.error("Error on flushing log writer: {}\nException will be ignored. name={}, fileName={}", e.getMessage(), auditProviderName, currentFileName);
            }
        }

        logger.debug("<== AbstractRangerAuditWriter.flush()");
    }

    public void createFileSystemFolders() throws Exception {
        logger.debug("==> AbstractRangerAuditWriter.createFileSystemFolders()");

        // Create a new file
        Date   currentTime = new Date();
        String fileName    = MiscUtil.replaceTokens(logFileNameFormat, currentTime.getTime());

        parentFolder = MiscUtil.replaceTokens(logFolder, currentTime.getTime());
        fullPath     = parentFolder + Path.SEPARATOR + fileName;

        String defaultPath = fullPath;

        conf = createConfiguration();

        URI uri = URI.create(fullPath);

        fileSystem       = FileSystem.get(uri, conf);
        auditPath        = new Path(fullPath);
        fileSystemScheme = getFileSystemScheme();

        logger.info("Checking whether log file exists. {} Path={}, UGI={}", fileSystemScheme, fullPath, MiscUtil.getUGILoginUser());

        int i = 0;

        while (fileSystem.exists(auditPath)) {
            i++;

            int    lastDot   = defaultPath.lastIndexOf('.');
            String baseName  = defaultPath.substring(0, lastDot);
            String extension = defaultPath.substring(lastDot);

            fullPath  = baseName + "." + i + extension;
            auditPath = new Path(fullPath);

            logger.info("Checking whether log file exists. {} Path={}", fileSystemScheme, fullPath);
        }

        logger.info("Log file doesn't exists. Will create and use it. {} Path={}", fileSystemScheme, fullPath);

        // Create parent folders
        createParents(auditPath, fileSystem);

        currentFileName = fullPath;

        logger.debug("<== AbstractRangerAuditWriter.createFileSystemFolders()");
    }

    public Configuration createConfiguration() {
        Configuration conf = new Configuration();

        for (Map.Entry<String, String> entry : auditConfigs.entrySet()) {
            String key   = entry.getKey();
            String value = entry.getValue();

            // for ease of install config file may contain properties with empty value, skip those
            if (StringUtils.isNotEmpty(value)) {
                conf.set(key, value);
            }

            logger.info("Adding property to {} + config: {} => {}", fileSystemScheme, key, value);
        }

        logger.info("Returning {} Filesystem Config: {}", fileSystemScheme, conf);

        return conf;
    }

    public void createParents(Path pathLogfile, FileSystem fileSystem) throws Exception {
        logger.info("Creating parent folder for {}", pathLogfile);

        Path parentPath = pathLogfile != null ? pathLogfile.getParent() : null;

        if (parentPath != null && fileSystem != null && !fileSystem.exists(parentPath)) {
            fileSystem.mkdirs(parentPath);
        }
    }

    public void init(Properties props, String propPrefix) {
        logger.debug("==> AbstractRangerAuditWriter.init()");

        String logFolderProp = MiscUtil.getStringProperty(props, propPrefix + "." + PROP_FILESYSTEM_DIR);

        if (StringUtils.isEmpty(logFolderProp)) {
            logger.error("File destination folder is not configured. Please set {}.{}. name={}", propPrefix, PROP_FILESYSTEM_DIR, auditProviderName);

            return;
        }

        String logSubFolder = MiscUtil.getStringProperty(props, propPrefix + "." + PROP_FILESYSTEM_SUBDIR);

        if (StringUtils.isEmpty(logSubFolder)) {
            logSubFolder = "%app-type%/%time:yyyyMMdd%";
        }

        logFileNameFormat = MiscUtil.getStringProperty(props, propPrefix + "." + PROP_FILESYSTEM_FILE_NAME_FORMAT);
        fileRolloverSec   = MiscUtil.getIntProperty(props, propPrefix + "." + PROP_FILESYSTEM_FILE_ROLLOVER, fileRolloverSec);

        if (StringUtils.isEmpty(fileExtension)) {
            setFileExtension(PROP_FILESYSTEM_FILE_EXTENSION);
        }

        if (logFileNameFormat == null || logFileNameFormat.isEmpty()) {
            logFileNameFormat = "%app-type%_ranger_audit_%hostname%" + fileExtension;
        }

        reUseLastLogFile = MiscUtil.getBooleanProperty(props, propPrefix + "." + PROP_IS_APPEND_ENABLED, false);
        logFolder        = logFolderProp + "/" + logSubFolder;

        logger.info("logFolder = {}, destName = {}", logFolder, auditProviderName);
        logger.info("logFileNameFormat = {}, destName = {}", logFileNameFormat, auditProviderName);
        logger.info("config = {}", auditConfigs);
        logger.info("isAppendEnabled = {}", reUseLastLogFile);

        rolloverPeriod  = MiscUtil.getStringProperty(props, propPrefix + "." + PROP_FILESYSTEM_ROLLOVER_PERIOD);
        rollingTimeUtil = RollingTimeUtil.getInstance();

        //file.rollover.period is used for rolling over. If it could compute the next rollover time using file.rollover.period
        //it fallbacks to use file.rollover.sec for find next rollover time. If still couldn't find default will be 1day window for rollover.
        if (StringUtils.isEmpty(rolloverPeriod)) {
            rolloverPeriod = rollingTimeUtil.convertRolloverSecondsToRolloverPeriod(fileRolloverSec);
        }

        try {
            nextRollOverTime = rollingTimeUtil.computeNextRollingTime(rolloverPeriod);
        } catch (Exception e) {
            logger.warn("Rollover by file.rollover.period failed...will be using the file.rollover.sec for {} audit file rollover...", fileSystemScheme, e);

            rollOverByDuration = true;
            nextRollOverTime   = rollOverByDuration();
        }

        logger.debug("<== AbstractRangerAuditWriter.init()");
    }

    public void closeFileIfNeeded() {
        logger.debug("==> AbstractRangerAuditWriter.closeFileIfNeeded()");

        if (logWriter == null) {
            logger.debug("Log writer is null, aborting rollover condition check!");

            return;
        }

        if (System.currentTimeMillis() >= nextRollOverTime.getTime()) {
            logger.info("Closing file. Rolling over. name = {}, fileName = {}", auditProviderName, currentFileName);

            logWriter.flush();

            closeWriter();
            resetWriter();
            setNextRollOverTime();

            currentFileName  = null;
            auditPath        = null;
            fullPath         = null;
        }

        logger.debug("<== AbstractRangerAuditWriter.closeFileIfNeeded()");
    }

    public Date rollOverByDuration() {
        long rollOverTime = rollingTimeUtil.computeNextRollingTime(fileRolloverSec, nextRollOverTime);

        return new Date(rollOverTime);
    }

    public PrintWriter createWriter() throws Exception {
        logger.debug("==> AbstractRangerAuditWriter.createWriter()");

        if (logWriter == null) {
            boolean appendMode = false;

            // if append is supported and enabled via config param, reuse last log file
            if (auditPath != null && reUseLastLogFile && isAppendEnabled()) {
                try {
                    ostream    = fileSystem.append(auditPath);
                    appendMode = true;

                    logger.info("Appending to last log file. auditPath = {}", fullPath);
                } catch (Exception e) {
                    logger.error("Failed to append to file {} due to {}", fullPath, e.getMessage());
                    logger.info("Falling back to create a new log file!");
                }
            }

            if (!appendMode) {
                // Create the file to write
                logger.info("Creating new log file. auditPath = {}", fullPath);

                createFileSystemFolders();

                ostream = fileSystem.create(auditPath);
            }
            logWriter             = new PrintWriter(ostream);
            isHFlushCapableStream = ostream.hasCapability(StreamCapabilities.HFLUSH);
        }

        logger.debug("<== AbstractRangerAuditWriter.createWriter()");

        return logWriter;
    }

    /**
     * Closes the writer after writing audits
     **/
    public void closeWriter() {
        logger.debug("==> AbstractRangerAuditWriter.closeWriter()");

        if (ostream != null) {
            try {
                ostream.close();
            } catch (IOException e) {
                logger.error("Error closing the stream {}", e.getMessage());
            }
        }

        if (logWriter != null) {
            logWriter.close();
        }

        logger.debug("<== AbstractRangerAuditWriter.closeWriter()");
    }

    public void resetWriter() {
        logger.debug("==> AbstractRangerAuditWriter.resetWriter()");

        logWriter = null;
        ostream   = null;

        logger.debug("<== AbstractRangerAuditWriter.resetWriter()");
    }

    public boolean logFileToHDFS(File file) throws Exception {
        logger.debug("==> AbstractRangerAuditWriter.logFileToHDFS()");

        boolean ret = false;

        if (logWriter == null) {
            // Create the file to write
            createFileSystemFolders();

            logger.info("Copying the Audit File {} to HDFS Path {}", file.getName(), fullPath);

            Path destPath = new Path(fullPath);

            ret = FileUtil.copy(file, fileSystem, destPath, false, conf);
        }

        logger.debug("<== AbstractRangerAuditWriter.logFileToHDFS()");

        return ret;
    }

    public String getFileSystemScheme() {
        return logFolder.substring(0, (logFolder.indexOf(":"))).toUpperCase();
    }

    public void setFileExtension(String fileExtension) {
        this.fileExtension = fileExtension;
    }

    private void setNextRollOverTime() {
        if (!rollOverByDuration) {
            try {
                if (StringUtils.isEmpty(rolloverPeriod)) {
                    rolloverPeriod = rollingTimeUtil.convertRolloverSecondsToRolloverPeriod(fileRolloverSec);
                }

                nextRollOverTime = rollingTimeUtil.computeNextRollingTime(rolloverPeriod);
            } catch (Exception e) {
                logger.warn("Rollover by file.rollover.period failed", e);
                logger.warn("Using the file.rollover.sec for {} audit file rollover...", fileSystemScheme);

                nextRollOverTime = rollOverByDuration();
            }
        } else {
            nextRollOverTime = rollOverByDuration();
        }
    }

    private boolean isAppendEnabled() {
        try {
            return fileSystem.hasPathCapability(auditPath, CommonPathCapabilities.FS_APPEND);
        } catch (Throwable t) {
            logger.warn("Failed to check if audit log file {} can be appended. Will create a new file.", auditPath, t);
        }

        return false;
    }
}
