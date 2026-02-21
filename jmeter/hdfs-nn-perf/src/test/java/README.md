<!---
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->
# Measuring RMS enabled HDFS NN performance with Jmeter

HDFS NameNode Performance Measurement with RMS using Apache Jmeter
<!-- TOC -->
* [Measuring RMS enabled HDFS NN performance with Jmeter](#measuring-rms-enabled-hdfs-nn-performance-with-jmeter)
    * [Prerequisites](#prerequisites)
    * [Build](#build)
    * [Deploy](#deploy)
    * [Jmeter Installation](#jmeter-installation)
    * [Update changes to setup.properties after installation](#update-changes-to-setupproperties-after-installation)
    * [Create files in HDFS](#create-files-in-hdfs)
      * [init hdfs directories with permissions](#init-hdfs-directories-with-permissions)
      * [generate files (run this command only from one of the remote hosts configured)](#generate-files--run-this-command-only-from-one-of-the-remote-hosts-configured-)
      * [verify count of existing files using this command](#verify-count-of-existing-files-using-this-command)
    * [Auto-Pilot Mode](#auto-pilot-mode)
    * [Manual Mode](#manual-mode)
      * [Run Workers [with jmeter-server]](#run-workers-with-jmeter-server)
      * [Run Master [with jmeter]](#run-master-with-jmeter)
<!-- TOC -->
### Prerequisites
- Java 8
- Maven 3.6.3
- Jmeter 5.6.2
- HDFS 

### Build
- Update `src/main/resources/setup.properties` with appropriate values  
- Remove all comments (the license header) in `rsa.pem` and update the contents with the private key to be used for authentication across hosts. 
- `mvn clean package`

### Deploy
- Create a working directory on master node using the value for `jmeter.install.dir` in `setup.properties` 
- Copy over `ranger-{version}-jmeter-hdfs-nn-perf.tar.gz`  to the master node
  - `scp -i rsa.pem ranger-{version}-jmeter-hdfs-nn-perf.tar.gz user@<destination.host>:<jmeter.install.dir>`  
- Ensure `hdfs.conf.dir` contains the path to the directory where `core-site.xml` and `hdfs-site.xml` are present and are accessible from the same node
### Jmeter Installation
> Ensure `hdfs.conf.dir` is accessible from the master node

Run this script on master node to install jmeter and set up the environment on all hosts:  
```
tar -xf ranger-{version}-jmeter-hdfs-nn-perf.tar.gz -C . --strip-components 1

# do this on all remote hosts i.e ${jmeter.remote.hosts}
mkdir {jmeter.install.dir}

chmod +x install-jmeter.sh && ./install-jmeter.sh master
```

This installs `jmeter` in the working directory  
This also un tars the `hdfs-nn-perf` library and makes `run-worker.sh` and `run-master.sh` available  
All configurations are driven from `setup.properties`

### Update changes to setup.properties after installation
If changes are made to `setup.properties` on the master node after installation, this step ensures the changes are reflected on the remote hosts (workers)  

```
chmod +x install-jmeter.sh && ./install-jmeter.sh update
```

### Create files in HDFS
HDFS files need to be created if they do not exist before running Jmeter tests  
> Note: `hdfs.rw.path.prefix` and `hdfs.rename.temp.dir` in `setup.properties` should refer to distinct non-existent directories  
#### init hdfs directories with permissions
```
chmod +x install-jmeter.sh && ./install-jmeter.sh init
```

#### generate files (run this command only from one of the remote hosts configured)
  ```
  # for 100k files
  echo -e "0\n100000" > index.txt
  chmod +x install-jmeter.sh && ./install-jmeter.sh create
  ```

#### verify count of existing files using this command
```
hadoop fs -count /abc/read /abc/write /abc/rename /abc/append /abc/delete /abc/status
```  

### Auto-Pilot Mode
Run the below from any of the remote hosts configured.
```
python3 run_jmeter_perf_tests.py
```
Currently, this runs 4 different combinations of rms and optimization flags on a particular RW config driven from `setup.properties`.  
Summary of the results are saved in `jmeter_run_summary.csv`. `jmeter-perf.log` captures the automated run logs whereas logs for a specific run are saved in the config named directory.

### Manual Mode

#### Run Workers [with jmeter-server]
```
./run-worker.sh 
```
To save the output use below:
```
nohup ./run-worker.sh > worker.out 2>&1 &
```

**Recover Deleted Files**  

Files deleted in the last run can be re-created using the below command after the jmeter run is complete  
> Note: The command should be run on all hosts where deletes were configured
```
chmod +x install-jmeter.sh && ./install-jmeter.sh recover
```

#### Run Master [with jmeter]
```
./run-master.sh
```
To save the output use below:
```
nohup ./run-master.sh > master.out 2>&1 &
```