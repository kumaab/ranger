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

current_host=$(hostname)
host_key_checking="-o StrictHostKeyChecking=no"
prop() {
    grep "^${1}" setup.properties | cut -d'=' -f2 | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
}

# timestamped logs
log() {
  if [ "$1" == "INFO" ]; then
    echo "$(date +'%Y-%m-%d %H:%M:%S') [$1]  - $2"
  else
    echo "$(date +'%Y-%m-%d %H:%M:%S') [$1] - $2"
  fi
}

run_scp() {
  local file_name="$1"
  local host="$2"
  local install_dir="$3"
  local is_sudo="$4"

  # Execute scp
  if [ "${is_sudo}" == "true" ]; then
    output=$(sudo scp "${host_key_checking}" -i "${pem_file}" "${file_name}" "$user@$host:$install_dir/" 2>&1)
  else
    output=$(scp "${host_key_checking}" -i "${pem_file}" "${file_name}" "$user@$host:$install_dir/" 2>&1)
  fi

  # Check the exit status of scp
  if [ $? -eq 0 ]; then
      log "INFO" "File ${file_name} copied to host ${host}"
  else
      log "ERROR" "Failed to copy file ${file_name} to host ${host}"
  fi
}

run_ssh() {
  local host="$1"
  local ssh_command="$2"
  local message="$3"

  # Execute ssh
  ssh "${host_key_checking}" -i "${pem_file}" "$user@$host" "$ssh_command" 2>&1

  # Check the exit status of ssh
  if [ $? -eq 0 ]; then
      log "INFO" "${message}"
  else
      log "ERROR" "SSH: Failed while performing operation ${message} on host ${host}"
  fi
}

install_jmeter() {
  current_dir=$(pwd)
  cd "${jmeter_install_dir}" || exit

  if [ ! -d apache-jmeter-"${jmeter_version}" ]; then
    log "INFO" "Installing Jmeter ..."
    if ! (wget -q https://dlcdn.apache.org//jmeter/binaries/apache-jmeter-"${jmeter_version}".tgz --no-check-certificate > /dev/null 2>&1)
    then
      log "ERROR" "Jmeter download failed!"
    else
      log "INFO" "Jmeter download complete!"
    fi
    log "INFO" "Unzipping Jmeter tarball .."
    if ! tar -xf apache-jmeter-"${jmeter_version}".tgz
    then
      log "ERROR" "Jmeter unzip failed!"
      exit 1
    else
      log "INFO" "Jmeter unzip complete!"
    fi
    rm -f apache-jmeter-"${jmeter_version}".tgz
  else
    log "INFO" "Apache Jmeter installation present, skipping re-install!"
  fi

  cd "${current_dir}" || exit
}

generate_jmx() { # Generate test plan .jmx file
  log "INFO" "Generating JMX Test Plan ..."
  java -cp "*:lib/*:conf/*:test-classes" org.apache.ranger.nn.perf.GenerateTestPlan 2>&1 |
  while IFS= read -r line; do
    log "INFO" "$line"
  done
}

generate_rmi_keystore() {
  keystore_file=rmi_keystore.jks
  if [ -f "${keystore_file}" ]; then
    log "INFO" "Key Store File ${keystore_file} exists, skipping generation!"
  else
    # Generate rmi_keystore.jks
    echo "a
    b
    c
    d
    e
    f
    y" | apache-jmeter-"${jmeter_version}"/bin/create-rmi-keystore.sh > /dev/null 2>&1
    log "INFO" "RMI keystore created successfully"
  fi
}

# requires distro.tar.gz to be available on master node.
install_jmeter_on_remote_hosts() {
  log "INFO" "Started Jmeter Installation on remote hosts .."
  IFS=',' read -ra values <<< "$hosts"
  current_modified=$(stat -c %Y "${distro_tar}")

  if [ -f ".last_modified" ]; then
    last_modified=$( < ".last_modified")
  else
    last_modified=0
  fi

  if [ "$last_modified" -lt "$current_modified" ]; then
    # Copy over distribution tarball to remote hosts
    for host in "${values[@]}"; do
      if [ "$host" != "$current_host" ]; then
        run_scp "${distro_tar}" "${host}" "${jmeter_install_dir}" "true"
      fi
    done
  fi
  echo "$current_modified" > ".last_modified"

  host_id=0
  for host in "${values[@]}"; do
    # create install dir and conf dir
    create_dir_cmd="if [ ! -d ${jmeter_install_dir} ]; then mkdir -p ${jmeter_install_dir}; fi"
    run_ssh "${host}" "$create_dir_cmd" "Verified install directory on host ${host}"

    create_conf_dir_cmd="if [ ! -d ${jmeter_install_dir}/conf ]; then mkdir ${jmeter_install_dir}/conf; fi"
    run_ssh "${host}" "$create_conf_dir_cmd" "Verified conf directory on host ${host}"

    # write host id to file
    run_ssh "${host}" "cd ${jmeter_install_dir}; echo ${host_id} > ${host_id_file}" "Writing host id on ${host} complete!"
    ((host_id += 1));

    if [ "$host" != "$current_host" ]; then
      # copy over rmi_keystore.jks
      run_scp "rmi_keystore.jks" "${host}" "${jmeter_install_dir}"

      # installation
      run_ssh "${host}" \
      "cd ${jmeter_install_dir};
       tar -xf ${distro_tar} -C . --strip-components 1;
       chmod +x install-jmeter.sh ; nohup ./install-jmeter.sh > install.log 2>&1"\
      "Completed Jmeter Installation on ${host}"
    fi
  done
}

update_changes() {
  testplan_file=$(prop 'test.plan.output')
  if [ ! -f "${testplan_file}" ]; then
    log "INFO" "${testplan_file} does not exist, run installation first, exiting!"
    exit 1
  fi
  threads=$(prop 'test.plan.threads')
  xml_threads=$(grep -oP '<intProp name=\"ThreadGroup.num_threads\">\K[^<]*' "${testplan_file}")
  if [ "${threads}" != "${xml_threads}" ]; then
    generate_jmx
  fi
  IFS=',' read -ra values <<< "$hosts"
  host_id=0
  for host in "${values[@]}"; do
    if [ "$host" != "$current_host" ]; then
      run_scp "${cfg_file}" "${host}" "${jmeter_install_dir}" "true"
    fi
    run_ssh "${host}" "cd ${jmeter_install_dir}; echo ${host_id} > ${host_id_file}" "Writing host id on ${host} complete!"
    ((host_id += 1));
  done
}

post_run_clean_up() {
  rm -f worker.out jmeter-server.log jmeter.log result.jtl
  if [ "$1" == 'master' ]; then
    return
  fi
  pid=$(ps -ef  | grep -v grep | grep "ApacheJMeter" | awk '{print $2}')
  # force kill nohup process
  kill $pid
  return
}