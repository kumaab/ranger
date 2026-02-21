#!/bin/bash
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

source jmeter-utils.sh

copy_over_hdfs_config_files() {
  IFS=',' read -ra values <<< "$hosts"
  # copy over core-site.xml and hdfs-site.xml
  for host in "${values[@]}"; do
    run_scp "${hadoop_conf_dir}"/core-site.xml "${host}" "${jmeter_install_dir}/conf/" "true"
    run_scp "${hadoop_conf_dir}"/hdfs-site.xml "${host}" "${jmeter_install_dir}/conf/" "true"
  done
}

create_files() {
  arg=$1
  java -cp "*:lib/*:conf/*:test-classes" org.apache.ranger.nn.perf.JmeterSampler "$arg"
}

hdfs_dir_init() {
  base_dir=$1
  rename_temp_dir=$2
  kinit -kt /cdep/keytabs/hdfs.keytab hdfs@ROOT.COMOPS.SITE
  hdfs dfs -mkdir -p "${base_dir}"/read "${base_dir}"/write "${base_dir}"/rename "${base_dir}"/delete \
  "${base_dir}"/status "${base_dir}"/append "${rename_temp_dir}"
  hdfs dfs -chmod 777 "${base_dir}"/read "${base_dir}"/write "${base_dir}"/rename "${base_dir}"/delete \
   "${base_dir}"/status "${base_dir}"/append "${rename_temp_dir}"
}

host_id_file=".hostId"
cfg_file="setup.properties"
distro_tar=$(ls *.tar.gz 2>/dev/null)

# read config variables
user=$(prop 'unix.user')
pem_file=$(prop 'pem.file')
java_home=$(prop 'java.home')
hosts=$(prop 'jmeter.remote.hosts')
jmeter_version=$(prop 'jmeter.version')
hadoop_conf_dir=$(prop 'hdfs.conf.dir')
jmeter_install_dir=$(prop 'jmeter.install.dir')
hdfs_rw_path_prefix=$(prop 'hdfs.rw.path.prefix')
hdfs_rename_temp_dir=$(prop 'hdfs.rename.temp.dir')

export JMETER_HOME=${jmeter_install_dir}/apache-jmeter-${jmeter_version}
export JAVA_HOME=${java_home}
export PATH=${JMETER_HOME}/bin:$JAVA_HOME/bin:$PATH

if [ "$1" == "update" ]; then
  update_changes
  exit 0
fi

if [ "$1" == "create" ] || [ "$1" == "recover" ] ; then
  create_files "$1"
  exit 0
fi

main() {
  if [ "$1" == "clean" ]; then
    post_run_clean_up
    exit 0
  fi

  if [ "$1" == "init" ]; then
    hdfs_dir_init "${hdfs_rw_path_prefix}", "${hdfs_rename_temp_dir}"
    exit 0
  fi

  chmod 400 "${pem_file}"

  if [ ! -d "${jmeter_install_dir}" ]; then
      log "INFO" "Jmeter Installation directory does not exist: ${jmeter_install_dir}, creating now!"
      mkdir "${jmeter_install_dir}"
      if [ $? -eq 0 ]; then
          log "INFO" "Jmeter install directory: ${jmeter_install_dir} created!"
          cd "${jmeter_install_dir}" || exit
      fi
  fi

  install_jmeter

  if [ "$1" == "master" ]; then
    generate_rmi_keystore
    install_jmeter_on_remote_hosts
    copy_over_hdfs_config_files
    generate_jmx
  fi

  chmod +x run-worker.sh
  chmod +x run-master.sh
}

main "$@"