#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License. See accompanying LICENSE file.
#
import os
import csv
import sys
import time
import logging
import requests
import argparse
import paramiko
import threading
import subprocess
import configparser
from datetime import datetime, timedelta

logging.basicConfig(filename='jmeter-perf.log', level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')


class LogFile(object):
    def __init__(self, logger, level):
        self.logger = logger
        self.level = level

    def write(self, message):
        if message.rstrip() != "":
            self.logger.log(self.level, message.rstrip())

    def flush(self):
        pass


logger = logging.getLogger('file_logger')
sys.stdout = LogFile(logger, logging.INFO)

filename = "worker.out"
metric_names = ['rpc_processing_time_avg_time', 'rpc_queue_time_avg_time']

session = requests.Session()
session.auth = ('admin', 'admin')
session.verify = False


def read_properties(file_path):
    config = configparser.ConfigParser()

    with open(file_path, 'r') as f:
        file_content = f.readlines()

    # Normalize the content by removing spaces around the "="
    normalized_content = '[default]\n'
    for line in file_content:
        if '=' in line:
            key, value = map(str.strip, line.split('=', 1))
            normalized_content += f"{key} = {value}\n"
        else:
            normalized_content += line  # Add lines without '=' as is

    # Read the normalized content
    config.read_string(normalized_content)

    # Access the key-value pairs under the default section
    properties = dict(config.items('default'))
    return properties


def run_remote_command(username, hostname, private_key, command, output_file):
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    key = paramiko.RSAKey.from_private_key_file(private_key)
    try:
        client.connect(hostname=hostname, username=username, pkey=key)
        stdin, stdout, stderr = client.exec_command(command)
        if output_file is not None:
            with open(output_file, 'w') as f:
                f.write(stdout.read().decode('utf-8'))
                f.write(stderr.read().decode('utf-8'))

        exit_status = stdout.channel.recv_exit_status()
    finally:
        client.close()


def run_remote_command_async(username, hostname, private_key, command, output_file):
    thread = threading.Thread(target=run_remote_command, args=(username, hostname, private_key, command, output_file))
    thread.start()
    return thread


def stop_hdfs(hostname):
    resp = session.post("http://{}:7180/api/v1/clusters/Cluster%201/services/HDFS-1/commands/stop".format(hostname))
    assert resp.status_code == 200, "Failed to stop HDFS"
    return resp


def start_hdfs(hostname):
    resp = session.post("http://{}:7180/api/v1/clusters/Cluster%201/services/HDFS-1/commands/start".format(hostname))
    assert resp.status_code == 200, "Failed to start HDFS"
    return resp


def update_hdfs_config(hostname, rms, optimization):
    xml_string = None
    with open('safety-valve-configs/rms-{}-optimization-{}.xml'.format(rms, optimization), 'r') as file:
        xml_string = file.read()
    data = {
        "items": [
            {
                "method": "PUT",
                "url": "/api/v31/clusters/Cluster%201/services/HDFS-1/config?message=Modified%20HDFS%20Service%20"
                       "Advanced%20Configuration%20Snippet%20(Safety%20Valve)%20for%20ranger-hdfs-security.xml",
                "body": {
                    "items": [
                        {
                            "name": "ranger_security_safety_valve",
                            "value": xml_string
                        }
                    ]
                },
                "contentType": "application/json"
            }
        ]}
    resp = session.post("http://{}:7180/api/v15/batch".format(hostname), json=data)
    assert resp.status_code == 200, "Failed to update rms = {} optimization = {} configs in HDFS" \
        .format(rms, optimization)
    print(f'Successfully updated with rms = {rms} optimization = {optimization} configs in ranger-hdfs-security.xml')
    return resp


def wait_for_hdfs_command_completion(hostname, command, sleep_dur):
    while True:
        resp = session.get("http://{}:7180/api/v56/clusters/Cluster%201/services/HDFS-1/commands?name={}"
                           .format(hostname, command))
        json_resp = resp.json()
        if len(json_resp["items"]) > 0:
            print(f'Command {command} is still running!')
            print("Command " + json_resp["items"][0]["name"] + ", active = " + str(json_resp["items"][0]["active"]))
            time.sleep(sleep_dur)
        else:
            print(f'Command {command} completed!')
            break
    return


def update_hdfs_and_restart(hostname, name_node, private_key, rms='yes', optimization='yes'):
    print('Proceeding to STOP hdfs, UPDATE configs and START hdfs ..')
    # stop hdfs
    resp = stop_hdfs(hostname)
    if resp.status_code == 200:
        wait_for_hdfs_command_completion(hostname, "Stop", 2)

        # cleanup log files from the previous run
        run_remote_command("root", name_node, private_key, "cd /; chmod +x cleanup.sh; ./cleanup.sh", "cleanup.log")

        resp = update_hdfs_config(hostname, rms=rms, optimization=optimization)

        if resp.status_code == 200:
            # start hdfs
            resp = start_hdfs(hostname)
            if resp.status_code == 200:
                wait_for_hdfs_command_completion(hostname, "Start", 20)
    return


def get_metrics_from_cm(hostname, entity, start_time, end_time):
    metrics_as_string = ','.join(metric_names)
    resp = session.get("http://{}:7180/api/v6/timeseries?"
                       "query=select {} where entityName={}&contentType\=text/csv&from={}&to={}"
                       .format(hostname, metrics_as_string, entity, start_time, end_time))
    assert resp.status_code == 200, "Failed to fetch metrics from CM"
    return resp.json()


def get_metrics_from_file(filename):
    results = {}
    with open(filename) as file:
        for line in file:
            try:
                start_index, end_index, ops_index = line.find("@"), line.find("UTC"), line.find("performed")
                if start_index != -1 and end_index != -1 and 'start_timestamp' not in results:
                    start_index += 1
                    end_index += 3
                    timestamp_str = line[start_index:end_index].strip()
                    timestamp = datetime.strptime(timestamp_str, "%b %d, %Y %I:%M:%S %p UTC")
                    results['start_timestamp'] = timestamp
                    results['start_time_str'] = timestamp.strftime("%Y-%m-%dT%H:%M:%S.000Z")
                    results['start_timestamp_conv'] = timestamp + timedelta(seconds=30)
                    results['start_time_conv_str'] = results['start_timestamp_conv'].strftime("%Y-%m-%dT%H:%M:%S.000Z")
                if start_index != -1 and end_index != -1 and 'end_timestamp' not in results:
                    start_index += 1
                    end_index += 3
                    timestamp_str = line[start_index:end_index].strip()
                    timestamp = datetime.strptime(timestamp_str, "%b %d, %Y %I:%M:%S %p UTC")
                    results['end_timestamp'] = timestamp
                    results['end_time_str'] = timestamp.strftime("%Y-%m-%dT%H:%M:%S.000Z")
                    results['end_timestamp_conv'] = timestamp - timedelta(seconds=30)
                    results['end_time_conv_str'] = results['end_timestamp_conv'].strftime("%Y-%m-%dT%H:%M:%S.000Z")
                if ops_index != -1:
                    ops_index += 10
                    operations = int(line[ops_index:].strip())
                    results['operations'] = operations
            except ValueError:
                continue

    assert 'end_timestamp' in results, "Failures encountered during the run, please check the logs!"

    run_duration_in_seconds = (results['end_timestamp'] - results['start_timestamp']).seconds
    assert run_duration_in_seconds > 120, "Insufficient run duration to capture results from CM:" \
                                          " {} seconds, Min required: 2 minutes".format(run_duration_in_seconds)
    return results


def recover_files():
    if os.path.exists('index.txt'):
        with open('index.txt', 'r') as index_file:
            contents = index_file.read().strip().split('\n')
            if len(contents) == 2 and all(num.isdigit() for num in contents):
                with open('recovered.log', 'w') as output_file:
                    subprocess.run(['chmod', '+x', 'install-jmeter.sh'], stdout=output_file, stderr=subprocess.PIPE)
                    subprocess.run(['./install-jmeter.sh', 'recover'], stdout=output_file, stderr=subprocess.PIPE)
                print(f'Recovered files with indices {contents[0]} to {contents[1]}, logs saved to recovered.log file')
            else:
                print("Error: index.txt should contain two numbers separated by '\\n'.")
    else:
        print("Error: index.txt not found.")


def print_results_summary(file_results, cm_results, configs):
    # pprint.pprint(file_results)
    run_metrics = {}
    time_diff = file_results['end_timestamp'] - file_results['start_timestamp']
    hours, remainder = divmod(time_diff.seconds, 3600)
    minutes, seconds = divmod(remainder, 60)
    print('Run Started at       : ' + file_results['start_time_str'])
    print('Run Ended at         : ' + file_results['end_time_str'])
    if hours > 0:
        print(f'Run Duration         : {hours} hours {minutes} minutes {seconds} seconds')
    else:
        print(f'Run Duration         : {minutes} minutes {seconds} seconds')
    print('Number of operations : ' + str(file_results['operations']))
    print('Run Duration(in secs): ' + str(time_diff.seconds))
    print('Throughput           : ' + str(file_results['operations'] / time_diff.seconds))

    for metric_name in metric_names:
        for iter in cm_results['items'][0]['timeSeries']:
            received_metric = iter['metadata']['metricName']
            if metric_name == received_metric:
                average_x = sum(sample['value'] for sample in iter['data']) / len(iter['data'])
                average_x_rounded = "%.3f" % average_x
                print(f'{metric_name} : {average_x_rounded}')
                run_metrics[metric_name] = average_x_rounded

    run_metrics['start_time'] = file_results['start_time_str']
    run_metrics['end_time']   = file_results['end_time_str']
    run_metrics['tps']        = "%.3f" % (file_results['operations'] / time_diff.seconds)
    run_metrics['rms_enabled'] = configs['is_rms_enabled']
    run_metrics['optimization_enabled'] = configs['is_optimization_enabled']
    run_metrics['read_write_mix'] = configs['read_write_mix']
    return run_metrics


def start_jmeter(args, props):
    user_name = props['unix.user']
    private_key = props['pem.file']
    remote_hosts = [remote_host.strip() for remote_host in props['jmeter.remote.hosts'].split(',')]
    started_processes = []
    for remote_host in remote_hosts:
        print(f'Starting Jmeter Server on host {remote_host} ..')
        thread = run_remote_command_async(user_name, remote_host, private_key,
                                          "cd {}; ./run-worker.sh".format(props['jmeter.install.dir']), filename)
        started_processes.append(thread)
        print(f'Started Jmeter Server on host {remote_host}')

    # wait for processes to start
    print('Waiting 2 seconds before starting Jmeter..')
    time.sleep(2)
    print(f'Starting Jmeter on {args.master}')
    run_remote_command(user_name, args.master, private_key,
                       "cd {}; ./run-master.sh".format(props['jmeter.install.dir']), "master.out")
    print('Jmeter Run finished!')

    # terminate server processes
    for remote_host in remote_hosts:
        run_remote_command(user_name, remote_host, private_key,
                           "pid=$(ps -ef  | grep -v grep | grep \"ApacheJMeter\" | awk '{print $2}'); kill $pid", None)

    for thread in started_processes:
        thread.join()


def save_pre_run_safety_valve_configs(hostname):
    resp = session.get("http://{}:7180/api/v56/clusters/Cluster%201/services/HDFS-1/config".format(hostname))
    items_as_json = resp.json()["items"]
    if items_as_json is None:
        print('No Configs present!')

    is_safety_valve_config_present = False
    for item in items_as_json:
        if item["name"] == "ranger_security_safety_valve" and item["value"] is not None:
            print('Saving safety valve configs to pre_run_configs')
            with open('pre_run_configs', 'w') as f:
                f.write(item["value"])
            is_safety_valve_config_present = True
    if not is_safety_valve_config_present:
        print('No Safety Valve Configs present!')
    return


def save_results_to_csv(data):
    output_csv = "jmeter_run_summary.csv"
    file_exists = os.path.isfile(output_csv)
    with open(output_csv, 'a', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=data.keys())
        if not file_exists:
            writer.writeheader()
        writer.writerow(data)
    return


def save_logs(dir, is_rms_enabled, is_optimization_enabled, read_count, write_count, delete_count):
    files_to_save = ['worker.out', 'jmeter-server.log', 'master.out', 'cleanup.log']
    if delete_count > 0:
        files_to_save.append('recovered.log')

    run_directory_name = '{}/rms_{}_opt_{}_read_{}_write_{}'.format(dir, is_rms_enabled, is_optimization_enabled,
                                                                    read_count, write_count)
    subprocess.run(['mkdir', '-p', run_directory_name])
    timestamp = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")

    for file in files_to_save:
        new_file_name = f"{run_directory_name}/{timestamp}_{file}"
        subprocess.run(['mv', file, new_file_name])
    return


def execute(args, props):
    private_key = props['pem.file']
    read_op_names = ['hdfs.op.count.read', 'hdfs.op.count.file_status']
    op_names = ['hdfs.op.count.read', 'hdfs.op.count.write', 'hdfs.op.count.append', 'hdfs.op.count.delete',
                'hdfs.op.count.rename', 'hdfs.op.count.file_status', 'hdfs.op.count.list_files', 'hdfs.op.count.mkdir']
    configs = {}
    combinations = (
        {'rms': 'yes', 'opt': 'yes'},
        {'rms': 'no', 'opt': 'no'},
        {'rms': 'no', 'opt': 'yes'},
        {'rms': 'yes', 'opt': 'no'}
    )
    for combination in combinations:
        is_rms_enabled = combination['rms']
        is_optimization_enabled = combination['opt']
        total_ops = sum([int(props[op_name]) for op_name in op_names])
        read_ops  = sum([int(props[op_name]) for op_name in read_op_names])

        configs['is_rms_enabled'] = is_rms_enabled
        configs['is_optimization_enabled'] = is_optimization_enabled
        configs['read_write_mix'] = '{}_read_{}_write'.format(read_ops, total_ops - read_ops)

        print(f'-------- RUN START: rms_enabled={is_rms_enabled}'
              f' optimization_enabled={is_optimization_enabled} --------')

        update_hdfs_and_restart(args.hostname, args.name_node, private_key,
                                rms=is_rms_enabled, optimization=is_optimization_enabled)
        # wait for hdfs to be healthy in CM
        print('Waiting 60 seconds for HDFS to become healthy')
        time.sleep(60)
        start_jmeter(args, props)
        file_results = get_metrics_from_file(filename)
        cm_results = get_metrics_from_cm(args.hostname, args.entity_name,
                                         file_results['start_time_conv_str'], file_results['end_time_conv_str'])
        metrics = print_results_summary(file_results, cm_results, configs)
        save_results_to_csv(metrics)

        save_logs(props['jmeter.install.dir'], is_rms_enabled, is_optimization_enabled,
                  props['hdfs.op.count.read'], props['hdfs.op.count.write'], int(props['hdfs.op.count.delete']))
        # wait before recovery of files
        if int(props['hdfs.op.count.delete']) > 0:
            print('Waiting 90 seconds before recovering deleted files')
            time.sleep(90)
            recover_files()

        print('------------------------------------- RUN COMPLETE -------------------------------------')
        # add delay between runs to allow clean metrics from CM
        print('Waiting 4 minutes before the next run starts')
        time.sleep(240)


def main():
    parser = argparse.ArgumentParser(description="Get HDFS Performance Run Results")
    parser.add_argument('-host', '--hostname', help='hostname where CM is running in the cluster',
                        default='ccycloud-1.ak-large.root.comops.site')
    parser.add_argument('-nn', '--name_node', help='hostname where NameNode is running in the cluster',
                        default='ccycloud-2.ak-large.root.comops.site')
    parser.add_argument('-m', '--master', help='hostname where Jmeter Master should run',
                        default='ccycloud-1.ak-large.root.comops.site')
    parser.add_argument('-entity', '--entity_name', help='id of the namenode to connect to',
                        default='HDFS-1-NAMENODE-ea5cf0645bc1418428110962345f6687')
    # above can be found by querying this API and then finding this string ""name" : "HDFS-1-NAMENODE"
    # http://hostname:7180/api/v56/clusters/Cluster%201/services/HDFS-1/roles
    args = parser.parse_args()
    props = read_properties("setup.properties")
    save_pre_run_safety_valve_configs(args.hostname)
    execute(args, props)


if __name__ == '__main__':
    main()
