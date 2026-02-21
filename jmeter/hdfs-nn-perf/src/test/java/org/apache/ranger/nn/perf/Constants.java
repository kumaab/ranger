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

public class Constants {
    public static final String TEST_PLAN_NAME              = "test.plan.name";
    public static final String TEST_PLAN_JAVA_CLASS        = "test.plan.java.class";
    public static final String TEST_PLAN_THREAD_GROUP_NAME = "test.plan.thread.group.name";
    public static final String TEST_PLAN_THREADS           = "test.plan.threads";
    public static final String TEST_PLAN_RAMP_UP           = "test.plan.ramp.up";
    public static final String TEST_PLAN_LOOPS             = "test.plan.loops";
    public static final String TEST_PLAN_OUTPUT            = "test.plan.output";
    public static final String TEST_PLAN_RESULT            = "test.plan.result";
    public static final String HDFS_WRITE                  = "write";
    public static final String HDFS_READ                   = "read";
    public static final String HDFS_APPEND                 = "append";
    public static final String HDFS_DELETE                 = "delete";
    public static final String HDFS_RENAME                 = "rename";
    public static final String HDFS_FILE_STATUS            = "status";
    public static final String HDFS_MKDIR                  = "mkdir";
    public static final String HDFS_LIST_FILES             = "list_files";
    public static final String HDFS_OP_COUNT_READ          = "hdfs.op.count.read";
    public static final String HDFS_OP_COUNT_WRITE         = "hdfs.op.count.write";
    public static final String HDFS_OP_COUNT_APPEND        = "hdfs.op.count.append";
    public static final String HDFS_OP_COUNT_DELETE        = "hdfs.op.count.delete";
    public static final String HDFS_OP_COUNT_RENAME        = "hdfs.op.count.rename";
    public static final String HDFS_OP_COUNT_FILE_STATUS   = "hdfs.op.count.file_status";
    public static final String HDFS_OP_COUNT_MKDIR         = "hdfs.op.count.mkdir";
    public static final String HDFS_OP_COUNT_LIST_FILES    = "hdfs.op.count.list_files";
    public static final String HDFS_ITERATIONS             = "hdfs.iterations";
    public static final String HDFS_FILES_PROVISIONED      = "hdfs.files.provisioned";
    public static final String HDFS_RW_PATH_PREFIX         = "hdfs.rw.path.prefix";
    public static final String HDFS_FILE_SUFFIX            = "hdfs.file.suffix";
    public static final String HDFS_RENAME_TEMP_DIR        = "hdfs.rename.temp.dir";
    public static final String HDFS_CONF_DIR               = "hdfs.conf.dir";
    public static final String LOCAL_CONF_DIR              = "local.conf.dir";
    public static final String KERBEROS_PRINCIPAL          = "kerberos.principal";
    public static final String KERBEROS_KEYTAB             = "kerberos.keytab";
    public static final String PROPERTIES_FILE             = "setup.properties";
    public static final String JMETER_INSTALL_DIR          = "jmeter.install.dir";
    public static final String JMETER_VERSION              = "jmeter.version";
    public static final String JMETER_REMOTE_HOSTS         = "jmeter.remote.hosts";
    public static final String STRING_TO_APPEND            = "Hello World";

    private Constants() {
        //To Block instantiation
    }
}
