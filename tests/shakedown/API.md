# Using `shakedown` helper methods in your DC/OS tests


## Table of contents

  * [Usage](#usage)
  * [Methods](#methods)
    * General
      * [authenticate()](#authenticate)
      * [dcos_url()](#dcos_url)
      * [master_url()](#master_url)
      * [agents_url()](#agents_url)
      * [dcos_service_url()](#dcos_service_url)
      * [dcos_state()](#dcos_state)
      * [dcos_agents_state()](#dcos_agents_state)
      * [dcos_version()](#dcos_version)
      * [dcos_acs_token()](#dcos_acs_token)
      * [dcos_url_path()](#dcos_url_path)
      * [master_ip()](#master_ip)
    * Packaging
      * [install_package()](#install_package)
      * [install_package_and_wait()](#install_package_and_wait)
      * [uninstall_package()](#uninstall_package)
      * [uninstall_package_and_wait()](#uninstall_package_and_wait)
      * [uninstall_package_and_data()](#uninstall_package_and_data)
      * [get_package_versions()](#get_package_versions)
      * [package_installed()](#package_installed)
      * [get_package_repos()](#get_package_repos)
      * [add_package_repo()](#add_package_repo)
      * [remove_package_repo()](#remove_package_repo)
    * Cluster
      * [get_resources()](#get_resources)
      * [resources_needed()](#resources_needed)
      * [get_used_resources()](#get_used_resources)
      * [get_unreserved_resources()](#get_unreserved_resources)
      * [get_reserved_resources()](#get_reserved_resources)
      * [get_resources_by_role()](#get_resources_by_role)
      * [available_resources()](#available_resources)
      * [shakedown_canonical_version()](#shakedown_canonical_version)
      * [shakedown_version_less_than()](#shakedown_version_less_than)
      * [dcos_canonical_version()](#dcos_canonical_version)
      * [dcos_version_less_than()](#dcos_version_less_than)
      * [required_cpus()](#required_cpus)
      * [required_mem()](#required_mem)
      * [bootstrap_metadata()](#bootstrap_metadata)
      * [ui_config_metadata()](#ui_config_metadata)
      * [dcos_version_metadata()](#dcos_version_metadata)
      * [ee_version()](#ee_version)
      * [mesos_logging_strategy()](#mesos_logging_strategy)
      * [dcos_1_7](#dcos_1_7)
      * [dcos_1_8](#dcos_1_8)
      * [dcos_1_9](#dcos_1_9)
      * [dcos_1_10](#dcos_1_10)      
      * [strict](#strict)
      * [permissive](#permissive)
      * [disabled](#disabled)
    * Command execution
      * [run_command()](#run_command)
      * [run_command_on_master()](#run_command_on_master)
      * [run_command_on_agent()](#run_command_on_agent)
      * [run_command_on_leader()](#run_command_on_leader)
      * [run_command_on_marathon_leader()](#run_command_on_marathon_leader)
      * [run_dcos_command()](#run_dcos_command)
    * Docker
      * [docker_version()](#docker_version)
      * [docker_server_version()](#docker_server_version)
      * [docker_client_version()](#docker_client_version)
      * [create_docker_credentials_file()](#create_docker_credentials_file)
      * [distribute_docker_credentials_to_private_agents()](#distribute_docker_credentials_to_private_agents)
      * [prefetch_docker_image_on_private_agents()](#prefetch_docker_image_on_private_agents)
    * File operations
      * [copy_file()](#copy_file)
      * [copy_file_to_master()](#copy_file_to_master)
      * [copy_file_to_agent()](#copy_file_to_agent)
      * [copy_file_from_master()](#copy_file_from_master)
      * [copy_file_from_agent()](#copy_file_from_agent)
    * Services
      * [get_service()](#get_service)
      * [delete_persistent_data()](#delete_persistent_data)
      * [destroy_volumes()](#destroy_volumes)
      * [destroy_volume()](#destroy_volume)
      * [unreserve_resources()](#unreserve_resources)
      * [unreserve_resource()](#unreserve_resource)
      * [get_service_framework_id()](#get_service_framework_id)
      * [get_service_task()](#get_service_task)
      * [get_service_tasks()](#get_service_tasks)
      * [get_service_ips()](#get_service_ips)
      * [get_marathon_task()](#get_marathon_task)
      * [get_marathon_tasks()](#get_marathon_tasks)
      * [service_healthy()](#service_healthy)
      * [wait_for_service_endpoint()](#wait_for_service_endpoint)
      * [wait_for_service_endpoint_removal()](#wait_for_service_endpoint_removal)
    * Spinner
      * [wait_for()](#wait_for)
      * [time_wait()](#time_wait)
      * [wait_while_exceptions()](#wait_while_exceptions)
      * [elapse_time()](#elapse_time)
    * Tasks
      * [get_task()](#get_task)
      * [get_tasks()](#get_tasks)
      * [get_active_tasks()](#get_active_tasks)
      * [task_completed()](#task_completed)
      * [wait_for_task()](#wait_for_task)
      * [wait_for_task_property()](#wait_for_task_property)
      * [wait_for_task_property_value()](#wait_for_task_property_value)
      * [wait_for_dns()](#wait_for_dns)
    * ZooKeeper
      * [get_zk_node_data()](#get_zk_node_data)
      * [get_zk_node_children()](#get_zk_node_children)
      * [delete_zk_node()](#delete_zk_node)      
    * Marathon
      * [deployment_wait()](#deployment_wait)
      * [delete_all_apps()](#delete_all_apps)
      * [delete_all_apps_wait()](#delete_all_apps_wait)
      * [is_app_healthy()](#is_app_healthy)
      * [marathon_leader_ip()](#marathon_leader_ip)
      * [marathon_version()](#marathon_version)
      * [marthon_version_less_than()](#marthon_version_less_than)
      * [mom_version()](#mom_version)
      * [mom_version_less_than()](#mom_version_less_than)
      * [marathon_on_marathon()](#marathon_on_marathon)
      * [marathon_1_3](#marathon_1_3)
      * [marathon_1_4](#marathon_1_4)
      * [marathon_1_5](#marathon_1_5)
    * Masters
      * [partition_master()](#partition_master)
      * [reconnect_master()](#reconnect_master)
      * [disconnected_master()](#disconnected_master)
      * [wait_for_mesos_endpoint()](#wait_for_mesos_endpoint)
      * [get_all_masters()](#get_all_masters)
      * [master_leader_ip()](#master_leader_ip)
      * [get_all_master_ips()](#get_all_master_ips)
      * [start_master_http_service()](#start_master_http_service)
      * [kill_process_from_pid_file_on_master()](#kill_process_from_pid_file_on_master)
      * [master_http_service()](#master_http_service)
    * Security
      * [add_user()](#add_user)
      * [get_user()](#get_user)
      * [remove_user()](#remove_user)
      * [ensure_resource()](#ensure_resource)
      * [set_user_permission()](#set_user_permission)
      * [remove_user_permission()](#remove_user_permission)
      * [add_group()](#add_group)
      * [get_group()](#get_group)
      * [remove_group()](#remove_group)
      * [add_user_to_group()](#add_user_to_group)
      * [remove_user_from_group()](#remove_user_from_group)
      * [credentials()](#credentials)
      * [no_user()](#no_user)
      * [new_dcos_user()](#new_dcos_user)
      * [dcos_user()](#dcos_user)
    * Agents
      * [get_agents()](#get_agents)
      * [get_private_agents()](#get_private_agents)
      * [get_public_agents()](#get_public_agents)
      * [partition_agent()](#partition_agent)
      * [reconnect_agent()](#reconnect_agent)
      * [restart_agent()](#restart_agent)
      * [stop_agent()](#stop_agent)
      * [start_agent()](#start_agent)
      * [delete_agent_log()](#delete_agent_log)
      * [kill_process_on_host()](#kill_process_on_host)
      * [kill_process_from_pid_file_on_host()](#kill_process_from_pid_file_on_host)      
      * [disconnected_agent()](#disconnected_agent)
      * [required_private_agents()](#required_private_agents)
      * [required_public_agents()](#required_public_agents)
      * [private_agents](#private_agents)
      * [public_agents](#public_agents)
    * Network
      * [restore_iptables()](#restore_iptables)
      * [save_iptables()](#save_iptables)
      * [flush_all_rules()](#flush_all_rules)
      * [allow_all_traffic()](#allow_all_traffic)
      * [iptable_rules()](#iptable_rules)

## Usage

`from shakedown import *`


## Methods

### authenticate()

Authenticate against an EE DC/OS cluster using a username and password.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**username** | the username used for DC/OS authentication | str
**password** | the password used for DC/OS authentication | str

##### *example usage*

```python
# Authenticate against DC/OS, receive an ACS token
token = authenticate('root', 's3cret')
```

* [master_url()](#master_url)
* [agents_url()](#agents_url)

### dcos_url()

The URL to the DC/OS cluster under test.

##### *parameters*

None.

##### *example usage*

```python
# Print the DC/OS dashboard URL.
dcos_url = dcos_url()
print("Dashboard located at: " + dcos_url)
```

### master_url()

The URL to the mesos master on the DC/OS cluster under test.

##### *parameters*

None.

##### *example usage*

```python
master_url = master_url()
print("Master located at: " + master_url)
```


### agents_url()

The URL to the agents end point for the master on the DC/OS cluster under test.

##### *parameters*

None.

##### *example usage*

```python
agents_url = agents_url()
print("Agent state.json is located at: " + agents_url)
```


### dcos_service_url()

The URI to a named service.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**service** | the name of the service | str

##### *example usage*

```python
# Print the location of the Jenkins service's dashboard
jenkins_url = dcos_service_url('jenkins')
print("Jenkins dashboard located at: " + jenkins_url)
```


### dcos_state()

A JSON hash containing DC/OS state information.

#### *parameters*

None.

#### *example usage*

```python
# Print state information of DC/OS slaves.
state_json = json.loads(dcos_json_state())
print(state_json['slaves'])
```


### dcos_agents_state()

A JSON hash containing DC/OS state information for the agents.

#### *parameters*

None.

#### *example usage*

```python
# Print state information of DC/OS slaves.
state_json = dcos_agents_state()
print(state_json['slaves'])
```


### dcos_version()

The DC/OS version number.

##### *parameters*

None.

##### *example usage*

```python
# Print the DC/OS version.
dcos_version = dcos_version()
print("Cluster is running DC/OS version " + dcos_version)
```


### dcos_acs_token()

The DC/OS ACS token (if authenticated).

##### *parameters*

None.

##### *example usage*

```python
# Print the DC/OS ACS token.
token = dcos_acs_token()
print("Using token " + token)
```



### dcos_url_path()

Provides a DC/OS url for the provide path.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**url_path** | the url path | str

##### *example usage*

```python
url = shakedown.dcos_url_path('marathon/v2/apps')
response = dcos.http.request('get', url)
```


### master_ip()

The current Mesos master's IP address.

##### *parameters*

None.

##### *example usage*

```python
# What's our Mesos master's IP?
master_ip = master_ip()
print("Current Mesos master: " + master_ip)
```


### install_package()

Install a package.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**package_name** | the name of the package to install | str
package_version | the version of the package to install | str | *latest*
service_name | custom service name | str | `None`
options_file | a file containing options in JSON format | str | `None`
options_json | a dict containing options in JSON format | dict | `None`
wait_for_completion | wait for service to become healthy before completing? | bool | `False`
timeout_sec | how long in seconds to wait before timing out | int | `600`

##### *example usage*

```python
# Install the 'jenkins' package; don't wait the service to register
install_package('jenkins')
```


### install_package_and_wait()

Install a package, and wait for the service to register.

*This method uses the same parameters as [`install_package()`](#install_package)*


### uninstall_package()

Uninstall a package.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**package_name** | the name of the package to install | str
service_name | custom service name | str | `None`
all_instances | uninstall all instances? | bool | `False`
wait_for_completion | wait for service to become healthy before completing? | bool | `False`
timeout_sec | how long in seconds to wait before timing out | int | `600`

##### *example usage*

```python
# Uninstall the 'jenkins' package; don't wait for the service to unregister
uninstall_package('jenkins')
```



### uninstall_package_and_data()

Uninstall a package, and wait for the service to unregister.  The cleans up the
reserved resources, the reserved disk and zk entry associated with the service.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**package_name** | the name of the package to install | str
service_name | custom service name | str | `None`
role | role for the service if not <service>-role | str | `None`
principal | principal for the service if not <service>-principal | str | `None`
zk_node | zk node to delete for the service | str | `None`
timeout_sec | how long in seconds to wait before timing out | int | `600`

##### *example usage*

```python
uninstall_package_and_data('confluent-kafka', zk_node='/dcos-service-confluent-kafka')
```


### uninstall_package_and_wait()

Uninstall a package, and wait for the service to unregister.

*This method uses the same parameters as [`uninstall_package()`](#uninstall_package)*


### get_package_versions()

Returns the list of versions of a given package.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**package_name** | the name of the package to install | str

##### *example usage*

```python
versions = get_package_versions('marathon')
if '1.5.2' not in versions:
    raise VersionException("version is not in this universe")
```


### package_installed()

Check whether a specified package is currently installed.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**package_name** | the name of the package to install | str
service_name | custom service name | str | `None`

##### *example usage*

```python
# Is the 'jenkins' package installed?
if package_installed('jenkins'):
    print('Jenkins is installed!')
```


### add_package_repo()

Add a repository to the list of package sources.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**repo_name** | the name of the repository | str
**repo_url** | the location of the repository | str
index | the repository index order | int | *-1*

##### *example usage*

```python
# Search the Multiverse before any other repositories
add_package_repo('Multiverse', 'https://github.com/mesosphere/multiverse/archive/version-2.x.zip', 0)
```


### remove_package_repo()

Remove a repository from the list of package sources.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**repo_name** | the name of the repository | str

##### *example usage*

```python
# No longer search the Multiverse
remove_package_repo('Multiverse')
```


### get_package_repos()

Retrieve a dictionary describing the configured package source repositories.

##### *parameters*

None

##### *example usage*

```python
# Which repository am I searching through first?
repos = get_package_repos()
print("First searching " + repos['repositories'][0]['name'])
```


### get_resources()

Gets a Resource object which includes the current cpu and memory of the cluster

##### *parameters*

None.

##### *example usage*

```python

resources = get_resources()

if resources.cpus > 2:
  # do stuff
```


### get_used_resources()

Gets a Resource object which includes the amount of cpu and memory being used in the cluster.

##### *parameters*

None.

##### *example usage*

```python

resources = get_used_resources()

if resources.cpus > 2:
  # do stuff
```


### get_unreserved_resources()

Gets a Resource object which includes the amount of cpu and memory that is not currently reserved.

##### *parameters*

None.

##### *example usage*

```python

resources = get_unreserved_resources()

if resources.cpus > 2:
  # do stuff
```

### get_reserved_resources()

Gets a Resource object which includes the amount of cpu and memory that is currently reserved.
Role == None is all reserved and 'slave_public' would be the public resources.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
role | role of reservation | str | None


##### *example usage*

```python

resources = get_reserved_resources()

if resources.cpus > 2:
  # do stuff
```


### get_resources_by_role()

Gets a Resource object which includes the amount of cpu and memory that is currently reserved by
a role.  The default is *

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
role | role of reservation | str | None


##### *example usage*

```python

resources = get_resources_by_role()

if resources.cpus > 2:
  # do stuff
```


### available_resources()

Gets a Resource object which includes the amount of cpu and memory that is  currently available.  This equates to (get_resources() - get_used_resources()).

##### *parameters*

None.

##### *example usage*

```python

resources = available_resources()

if resources.cpus > 2:
  # do stuff
```

### resources_needed()

Run a command on a remote host via SSH.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
total_tasks | number of tasks | int | 1
per_task_cpu | cpu per task requirement | float | 0.01
per_task_mem | the username used for SSH authentication | float | 1

##### *example usage*

```python

```


### shakedown_canonical_version()

Provides a canonical version number of shakedown via distutils.version.LooseVersion.
Useful for shakedown verison comparisons.

##### *parameters*

None.

##### *example usage*

```python
@pytest.mark.skipif('shakedown_canonical_version() < LooseVersion("1.3")')
def test_1_3_specific_test():
```


### shakedown_version_less_than()

Returns True if the shakedown version is less than the provided version, otherwise
returns False.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
version | version string "1.3.3" | str

##### *example usage*

```python
@pytest.mark.skipif('shakedown_version_less_than("1.3.3")')
def test_1_3_3_specific_test():
```


### dcos_canonical_version()

Provides a canonical version number.  `dcos_version` returns a version string with a
few variations such as `1.9-dev`.  `dcos_canonical_version` returns a distutils.version.LooseVersion
and will strip `-dev` if present.  It can be used to determine if the DC/OS cluster version
is correct for the test or if it should be skipped.

##### *parameters*

None.

##### *example usage*

```python
@pytest.mark.skipif('dcos_canonical_version() < LooseVersion("1.9")')
def test_1_9_specific_test():
```


### dcos_version_less_than()

Returns True if the DC/OS version is less than the provided version, otherwise
returns False.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
version | version string "1.9.0" | str


##### *example usage*

```python
@pytest.mark.skipif('dcos_version_less_than("1.9")')
def test_1_9_specific_test():
```


### dcos_1_7

Preconfigured annotation which requires DC/OS 1.7+

##### *parameters*

None.

##### *example usage*

```python
# if the DC/OS cluster version 1.6 the test will be skipped
@dcos_1_7
def test_1_7_plus_feature():
```


### dcos_1_8

Preconfigured annotation which requires DC/OS 1.8+

##### *parameters*

None.

##### *example usage*

```python
# if the DC/OS cluster version 1.7 the test will be skipped
@dcos_1_8
def test_1_8_plus_feature():
```


### dcos_1_9

Preconfigured annotation which requires DC/OS 1.9+

##### *parameters*

None.

##### *example usage*

```python
# if the DC/OS cluster version 1.8 the test will be skipped
@dcos_1_9
def test_1_9_plus_feature():
```


### dcos_1_10

Preconfigured annotation which requires DC/OS 1.10+

##### *parameters*

None.

##### *example usage*

```python
# if the DC/OS cluster version 1.9 the test will be skipped
@dcos_1_10
def test_1_10_plus_feature():
```


### strict

Preconfigured annotation which requires DC/OS Enterprise in strict mode

##### *parameters*

None.

##### *example usage*

```python
# if the DC/OS enterprise cluster is not in strict mode it will be skipped
@strict
def test_strict_only_feature():
```


### permissive

Preconfigured annotation which requires DC/OS Enterprise in permissive mode

##### *parameters*

None.

##### *example usage*

```python
# if the DC/OS enterprise cluster is not in permissive mode it will be skipped
@permissive
def test_permissive_only_feature():
```


### disabled

Preconfigured annotation which requires DC/OS Enterprise in disabled mode

##### *parameters*

None.

##### *example usage*

```python
# if the DC/OS enterprise cluster is not in disabled mode it will be skipped
@disabled
def test_disabled_only_feature():
```


### required_cpus()

Returns True if the cluster resources are less than the specified number of cores,
otherwise returns False.  This is based on available resources.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
cpus | number of cpus | int
role | reservation role | str | *

##### *example usage*

```python
# skips test if there is only 1 core left in the cluster.
@pytest.mark.skipif('required_cpus(2)')
def test_requires_2_cores():
```


### required_mem()

Returns True if the cluster resources are less than the specified amount of memory,
otherwise returns False.  This is based on available resources.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
mem  | amount of mem    | int
role | reservation role | str | *


##### *example usage*

```python

requires_2_cores = pytest.mark.skipif('required_cpus(2)')

@dcos_1_9
@requires_2_cores
@pytest.mark.skipif('required_mem(512)')
def test_requires_512m_memory():
# requires DC/OS 1.9, 2 cores and 512M
```


### bootstrap_metadata()

Returns the JSON of the boostrap metadata for DC/OS Enterprise clusters.
Return None if DC/OS Open or DC/OS Version is < 1.9.
##### *parameters*

None.

##### *example usage*

```python
metadata = bootstrap_metadata()
if metadata:
  print(metadata['security'])
```


### ui_config_metadata()

Returns the JSON of the UI configuration metadata for DC/OS Enterprise clusters.
Return None if DC/OS Open or DC/OS Version is < 1.9.

##### *parameters*

None.

##### *example usage*

```python
metadata = ui_config_metadata()
if metadata:
  print(metadata['uiConfiguration']['plugins']['mesos']['logging-strategy'])
```


### dcos_version_metadata()

Returns the JSON of the DC/OS version metadata for DC/OS Enterprise clusters.
Returns None if not available.

##### *parameters*

None.

##### *example usage*

```python
metadata = dcos_version_metadata()
if metadata:
  print(metadata['dcos-image-commit'])
```


### ee_version()

Returns the DC/OS Enterprise version type which is {strict, permissive, disabled}
Return None if DC/OS Open or DC/OS Version is < 1.9.

##### *parameters*

None.

##### *example usage*

```python
@pytest.mark.skipif("ee_version() in {'strict', 'disabled'}")
def test_skips_strict_or_disabled():
```


### mesos_logging_strategy()

Returns the mesos logging strategy if available, otherwise None.

##### *parameters*

None.

##### *example usage*

```python
strategy = mesos_logging_strategy()
print(strategy)
```


### run_command()

Run a command on a remote host via SSH.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**host** | the hostname or IP to run the command on | str
**command** | the command to run | str
username | the username used for SSH authentication | str | `core`
key_path | the path to the SSH keyfile used for authentication | str | `None`
noisy    | Output to stdout if True | bool | True

##### *example usage*

```python
# I wonder what /etc/motd contains on the Mesos master?
exit_status, output = run_command(master_ip(), 'cat /etc/motd')
```


### run_command_on_master()

Run a command on the Mesos master via SSH.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**command** | the command to run | str
username | the username used for SSH authentication | str | `core`
key_path | the path to the SSH keyfile used for authentication | str | `None`
noisy    | Output to stdout if True | bool | True

##### *example usage*

```python
# What kernel is our Mesos master running?
exit_status, output = run_command_on_master('uname -a')
```


### run_command_on_agent()

Run a command on a Mesos agent via SSH, proxied via the Mesos master.

*This method uses the same parameters as [`run_command()`](#run_command)*

### run_command_on_leader()

Run a command on the Mesos master leader via SSH.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**command** | the command to run | str
username | the username used for SSH authentication | str | `core`
key_path | the path to the SSH keyfile used for authentication | str | `None`
noisy    | Output to stdout if True | bool | True

##### *example usage*

```python
# What kernel is our Mesos leader running?
exit_status, output = run_command_on_leader('uname -a')
```


### run_command_on_marathon_leader()

Run a command on the Marathon leader via SSH.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**command** | the command to run | str
username | the username used for SSH authentication | str | `core`
key_path | the path to the SSH keyfile used for authentication | str | `None`
noisy    | Output to stdout if True | bool | True

##### *example usage*

```python
# What kernel is our Marathon leader running?
exit_status, output = run_command_on_marathon_leader('uname -a')
```


### run_dcos_command()

Run a command using the `dcos` CLI.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**command** | the command to run | str

##### *example usage*

```python
# What's the current version of the Jenkins package?
stdout, stderr, return_code = run_dcos_command('package search jenkins --json')
result_json = json.loads(stdout)
print(result_json['packages'][0]['currentVersion'])
```


### docker_version()

Get the version of Docker [Server]

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
host | host or IP of the machine Docker is running on | str | `master_ip()`
component | which Docker version component to query (`server` or `client`) | str | `server`

##### *example usage*

```python
master_docker_version = docker_version()
print("DC/OS master is running Docker version {}".format(master_docker_version))
```


### docker_server_version()

Get the version of Docker Server

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
host | host or IP of the machine Docker is running on | str | `master_ip()`

##### *example usage*

```python
docker_server = docker_server_version()
print("DC/OS master is running Docker Server version {}".format(docker_server))
```


### docker_client_version()

Get the version of Docker Client

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
host | host or IP of the machine Docker is running on | str | `master_ip()`

##### *example usage*

```python
docker_client = docker_client_version()
print("DC/OS master is running Docker Client version {}".format(docker_client))
```


### create_docker_credentials_file()

Creates a docker credentials file for the provided username and password.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**username** | docker repo username | str
**password** | docker repo password | str
file_name | the compressed tar filename | str | docker.tar.gz

##### *example usage*

```python
create_docker_credentials_file('billy', 'secret')
```


### distribute_docker_credentials_to_private_agents()

Creates a docker credentials file for the provided username and password and
distributes it to all the private agents.  It deletes the file after distribution.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**username** | docker repo username | str
**password** | docker repo password | str
file_name | the compressed tar filename | str | docker.tar.gz

##### *example usage*

```python
distribute_docker_credentials_to_private_agents('billy', 'secret')
```


### prefetch_docker_image_on_private_agents()

Ensures that the docker image provided is prefetched to all the private agents.
This can decrease image pull time for subsequent tests.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**image** | docker image name | str

##### *example usage*

```python
prefetch_docker_image_on_private_agents('nginx')
```


### copy_file()

Copy a file via SCP.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**host** | the hostname or IP to copy the file to/from | str
**file_path** | the local path to the file to be copied | str
remote_path | the remote path to copy the file to | str | `.`
username | the username used for SSH authentication | str | `core`
key_path | the path to the SSH keyfile used for authentication | str | `None`
action | 'put' (default) or 'get' | str | `put`

##### *example usage*

```python
# Copy a datafile onto the Mesos master
copy_file(master_ip(), '/var/data/datafile.txt')
```


### copy_file_to_master()

Copy a file to the Mesos master.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**file_path** | the local path to the file to be copied | str
remote_path | the remote path to copy the file to | str | `.`
username | the username used for SSH authentication | str | `core`
key_path | the path to the SSH keyfile used for authentication | str | `None`

##### *example usage*

```python
# Copy a datafile onto the Mesos master
copy_file_to_master('/var/data/datafile.txt')
```


### copy_file_to_agent()

Copy a file to a Mesos agent, proxied through the Mesos master.

*This method uses the same parameters as [`copy_file()`](#copy_file)*


### copy_file_from_master()

Copy a file from the Mesos master.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**remote_path** | the remote path of the file to copy | str |
file_path | the local path to copy the file to | str | `.`
username | the username used for SSH authentication | str | `core`
key_path | the path to the SSH keyfile used for authentication | str | `None`

##### *example usage*

```python
# Copy a datafile from the Mesos master
copy_file_from_master('/var/data/datafile.txt')
```


### copy_file_from_agent()

Copy a file from a Mesos agent, proxied through the Mesos master.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**host** | the hostname or IP to copy the file from | str
**remote_path** | the remote path of the file to copy | str
file_path | the local path to copy the file to | str | `.`
username | the username used for SSH authentication | str | `core`
key_path | the path to the SSH keyfile used for authentication | str | `None`

##### *example usage*

```python
# Copy a datafile from an agent running Jenkins
service_ips = get_service_ips('marathon', 'jenkins')
for host in service_ips:
    assert copy_file_from_agent(host, '/home/jenkins/datafile.txt')
```


### get_service()

Retrieve a dictionary describing a named service.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**service_name** | the name of the service | str
inactive | include inactive services? | bool | `False`
completed | include completed services? | bool | `False`

##### *example usage*

```python
# Tell me about the 'jenkins' service
jenkins = get_service('jenkins')
```


### delete_persistent_data()

Delete the reserved_resources, destroys volumes and deletes the zk node for a given service.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**role** | the role for the service | str
**zk_node** | the zk node to delete | str

##### *example usage*

```python
delete_persistent_data('confluent-kafka-role', '/dcos-service-confluent-kafka')
```


### destroy_volumes()

Destroys the volume for the given role (on all slaves in the cluster).  
It is important to uninstall the service prior to calling this function.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**role** | the role associated with the service | str

##### *example usage*

```python
destroy_volumes('confluent-kafka-role')
```


### destroy_volume()

Destroys the volume for the given role on a give agent.
It is important to uninstall the service prior to calling this function.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**agent** | an agent id in the cluster | str
**role** | the role associated with the service | str

##### *example usage*

```python
destroy_volumes('a8571994-47f8-4590-8922-47f10886165a-S1', 'confluent-kafka-role')
```


### unreserve_resources()

Unreserve resources for the given role (on all slaves in the cluster).  
It is important to uninstall the service prior to calling this function.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**role** | the role associated with the service | str

##### *example usage*

```python
unreserve_resources('confluent-kafka-role')
```


### unreserve_resource()

Unreserve resources for the given role on a give agent.
It is important to uninstall the service prior to calling this function.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**agent** | an agent id in the cluster | str
**role** | the role associated with the service | str

##### *example usage*

```python
unreserve_resource('a8571994-47f8-4590-8922-47f10886165a-S1', 'confluent-kafka-role')
```


### get_service_framework_id()

Get the framework ID of a named service.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**service_name** | the name of the service | str
inactive | include inactive services? | bool | `False`
completed | include completed services? | bool | `False`

##### *example usage*

```python
# What is the framework ID for the 'jenkins' service?
jenkins_framework_id = get_framework_id('jenkins')
```


### get_service_task()

Get a dictionary describing a named service task.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**service_name** | the name of the service | str
**task_name** | the name of the task | str
inactive | include inactive services? | bool | `False`
completed | include completed services? | bool | `False`

##### *example usage*

```python
# Tell me about marathon's 'jenkins' task
jenkins_tasks = get_service_task('marathon', 'jenkins')
```


### get_service_tasks()

Get a list of task IDs associated with a named service.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**service_name** | the name of the service | str
inactive | include inactive services? | bool | `False`
completed | include completed services? | bool | `False`

##### *example usage*

```python
# What's marathon doing right now?
service_tasks = get_service_tasks('marathon')
```


### get_marathon_task()

Get a dictionary describing a named Marathon task.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**task_name** | the name of the task | str
inactive | include inactive services? | bool | `False`
completed | include completed services? | bool | `False`

##### *example usage*

```python
# Tell me about marathon's 'jenkins' task
jenkins_tasks = get_marathon_task('jenkins')
```


### get_marathon_tasks()

Get a list of Marathon tasks.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
inactive | include inactive services? | bool | `False`
completed | include completed services? | bool | `False`

##### *example usage*

```python
# What's marathon doing right now?
service_tasks = get_marathon_tasks()
```


### get_service_ips()

Get a set of the IPs associated with a service.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**service_name** | the name of the service | str
task_name | the name of the task to limit results to | str | `None`
inactive | include inactive services? | bool | `False`
completed | include completed services? | bool | `False`

##### *example usage*

```python
# Get all IPs associated with the 'chronos' task running in the 'marathon' service
service_ips = get_service_ips('marathon', 'chronos')
print('service_ips: ' + str(service_ips))
```

### service_healthy()

Check whether a specified service is currently healthy.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**service_name** | the name of the service | str

##### *example usage*

```python
# Is the 'jenkins' service healthy?
if service_healthy('jenkins'):
    print('Jenkins is healthy!')
```

### wait_for_service_endpoint()

Checks the service url returns HTTP 200 within a timeout if available it returns true on expiration it returns false.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**service_name** | the name of the service | str
timeout_sec | how long in seconds to wait before timing out | int | `120`

##### *example usage*

```python
# will wait
wait_for_service_endpoint("marathon-user")
```

### wait_for_service_endpoint_removal()

Checks the service url returns HTTP 500 within a timeout if available it returns true on expiration it returns time to remove.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**service_name** | the name of the service | str
timeout_sec | how long in seconds to wait before timing out | int | `120`

##### *example usage*

```python
# will wait
wait_for_service_endpoint_removal("marathon-user")
```

### wait_for()

Waits for a function to return true or times out.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**predicate** | the predicate function| fn
timeout_seconds | how long in seconds to wait before timing out | int | `120`
sleep_seconds | time to sleep between multiple calls to predicate | int | `1`
ignore_exceptions | ignore exceptions thrown by predicate | bool | True
inverse_predicate | if True look for False from predicate | bool | False

##### *example usage*

```python
# simple predicate
def deployment_predicate(client=None):
  ...

wait_for(deployment_predicate, timeout)

# predicate with a parameter
def service_available_predicate(service_name):
  ...

wait_for(lambda: service_available_predicate(service_name), timeout_seconds=timeout_sec)

```

### time_wait()

Waits for a function to return true or times out.  Returns the elapsed time of wait.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**predicate** | the predicate function| fn
timeout_seconds | how long in seconds to wait before timing out | int | `120`
sleep_seconds | time to sleep between multiple calls to predicate | int | `1`
ignore_exceptions | ignore exceptions thrown by predicate | bool | True
inverse_predicate | if True look for False from predicate | bool | False
noisy | boolean to increase debug output | bool | True
required_consecutive_success_count | the number of consecutive successes that required | int | 1


##### *example usage*

```python
# simple predicate
def deployment_predicate(client=None):
  ...

time_wait(deployment_predicate, timeout)

# predicate with a parameter
def service_available_predicate(service_name):
  ...

time_wait(lambda: service_available_predicate(service_name), timeout_seconds=timeout_sec)

```

### wait_while_exceptions()

Waits for a function to return without exception or time out.  Returns the return value of the function.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**predicate** | the predicate function| fn
timeout_seconds | how long in seconds to wait before timing out | int | `120`
sleep_seconds | time to sleep between multiple calls to predicate | int | `1`
noisy | boolean to increase debug output | bool | True


##### *example usage*

```python
# simple predicate
def deployment_predicate(client=None):
  ...

wait_while_exceptions(deployment_predicate, timeout)

# predicate with a parameter
def service_available_predicate(service_name):
  ...

wait_while_exceptions(lambda: service_available_predicate(service_name), timeout_seconds=timeout_sec)

```

### elapse_time()

returns the time difference with a given precision.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**start** | the start time | time
end | end time, if not provided current time is used | time | None
precision | the number decimal places to maintain | int | `3`

##### *example usage*

```python
# will wait
elapse_time("marathon-user")
```

### get_task()

Get information about a task.

*This method uses the same parameters as [`get_tasks()`](#get_tasks)*


### get_tasks()

Get a list of tasks, optionally filtered by task ID.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
task_id   | task ID     | str  |
completed | include completed tasks? | `True`

##### *example usage*

```python
# What tasks have been run?
tasks = get_tasks()
for task in tasks:
    print("{} has state {}".format(task['id'], task['state']))
```


### get_active_tasks()

Get a list of active tasks, optionally filtered by task name.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
task_id   | task ID     | str
completed | include completed tasks? | `False`

##### *example usage*

```python
# What tasks are running?
tasks = get_active_tasks()
for task in tasks:
    print("{} has state {}".format(task['id'], task['state']))
```

### task_completed()

Check whether a task has completed.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
task_id   | task ID     | str

##### *example usage*

```python
# Wait for task 'driver-20160517222552-0072' to complete
while not task_completed('driver-20160517222552-0072'):
    print('Task not complete; sleeping...')
    time.sleep(5)
```

### wait_for_task()

Wait for a task to be reported running by Mesos.  Returns the elapsed time of wait.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
service   | framework service name    | str
task      | task name | str
timeout_sec | timeout | int | `120`


##### *example usage*

```python
wait_for_task('marathon', 'marathon-user')
```

### wait_for_task_property()

Wait for a task to be report having a specific property.   Returns the elapsed time of wait.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
service   | framework service name    | str
task      | task name | str
prop      | property name | str
timeout_sec | timeout | int | `120`


##### *example usage*

```python
wait_for_task_property('marathon', 'chronos', 'resources')
```

### wait_for_task_property_value()

Wait for a task to be reported having a property with a specific value.  Returns the elapsed time of wait.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
service   | framework service name    | str
task      | task name | str
prop      | property name | str
value      | value of property | str
timeout_sec | timeout | int | `120`


##### *example usage*

```python
wait_for_task_property_value('marathon', 'marathon-user', 'state', 'TASK_RUNNING')
```

### wait_for_dns()

Wait for a task dns.  Returns the elapsed time of wait.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
name      | dns name    | str
timeout_sec | timeout | int | `120`

##### *example usage*

```python
wait_for_dns('marathon-user.marathon.mesos')
```

### delete_zk_node()

Delete a named ZooKeeper node.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
node_name | the name of the node | str

##### *example usage*

```python
# Delete a 'universe/marathon-user' ZooKeeper node
delete_zk_node('universe/marathon-user')
```

### get_zk_node_data()

Get data for a Zookeeper node.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
node_name | the name of the node | str

##### *example usage*

```python
# Get data for a 'universe/marathon-user' ZooKeeper node
get_zk_node_data('universe/marathon-user')
```

### get_zk_node_children()

Get child nodes for a Zookeeper node.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
node_name | the name of the node | str

##### *example usage*

```python
# Get children for a 'universe/marathon-user' ZooKeeper node
get_zk_node_children('universe/marathon-user')
```

### deployment_wait()

Waits for Marathon Deployment to complete or times out.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
timeout | max time to wait for deployment | int | 120
app_id | wait for deployments on this app | string | None

##### *example usage*

```python
# assuming a client.add_app() or similar
deployment_wait()
```

### delete_all_apps()

Deletes all apps running on Marathon.

##### *parameters*

None.

##### *example usage*

```python
delete_all_apps()
```

### delete_all_apps_wait()

Deletes all apps running on Marathon and waits for deployment to finish.

##### *parameters*

None.

##### *example usage*

```python
delete_all_apps_wait()
```


### is_app_healthy

Returns True if the given app is healthy.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
app_id | marathon app ID | String |

##### *example usage*

```python
is_app_healthy(app_id)
```


### marathon_leader_ip

Returns the IP address of the marathon leader.

##### *parameters*

None.

##### *example usage*

```python
ip = marathon_leader_ip()
```


### marathon_version

Returns the distutils.version.LooseVersion version of marathon.

##### *parameters*

None.

##### *example usage*

```python
@pytest.mark.skipif('marathon_version() < LooseVersion("1.4")')
def test_requires_marathon_1_4():
```


### marthon_version_less_than

Returns True if the marathon version is less than the version specified, otherwise
returns False.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
version | version str "1.4" | String |

##### *example usage*

```python
@pytest.mark.skipif('marthon_version_less_than("1.4")')
def test_requires_marathon_1_4():
```


### mom_version

Returns the distutils.version.LooseVersion version of marathon on marathon.  None if not present.

##### *parameters*

None.

##### *example usage*

```python
@pytest.mark.skipif('mom_version() < LooseVersion("1.4")')
def test_requires_mom_1_4():
```


### mom_version_less_than

Returns True if the marathon on marathon version is less than the version specified, otherwise
returns False.  Returns False if MoM not present.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
version | version str "1.4" | String |

##### *example usage*

```python
@pytest.mark.skipif('mom_version_less_than("1.4")')
def test_requires_mom_1_4():
```


### marathon_on_marathon

Sets the context of the dcos config such that calls to Marathon are against the
Marathon on Marathon.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
name | name of the MoM service | String | marathon-user

##### *example usage*

```python
  with marathon_on_marathon():
    client = marathon.create_client()
    # this the client is connected to MoM
    about = client.get_about()
```


### marathon_1_3

Preconfigured annotation which requires marathon 1.3+

##### *parameters*

None.

##### *example usage*

```python
# skips test if marathon 1.2 otherwise runs
@marathon_1_3
def test_requires_marathon_1_3():
```


### marathon_1_4

Preconfigured annotation which requires marathon 1.4+

##### *parameters*

None.

##### *example usage*

```python
# skips test if marathon 1.3 otherwise runs
@marathon_1_4
def test_requires_marathon_1_4():
```


### marathon_1_5

Preconfigured annotation which requires marathon 1.5+

##### *parameters*

None.

##### *example usage*

```python
# skips test if marathon 1.4 otherwise runs
@marathon_1_5
def test_requires_marathon_1_5():
```


### partition_master()

Separates the master from the cluster by disabling inbound and/or outbound traffic.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
incoming | disable incoming traffic? | bool | `True`
outgoing | disable outgoing traffic? | bool | `True`

##### *example usage*

```python
# Disable incoming traffic ONLY to the DC/OS master.
partition_master(True, False)
```


### reconnect_master()

Reconnect a previously partitioned master to the network

##### *parameters*

None.

##### *example usage*

```python
# Reconnect the master.
reconnect_master()
```

### add_user()

Adds user to the DCOS Enterprise.  If not description is provided the uid will
be used for the description.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**uid** | user id | str
**password** | password | str
desc | description for user | str | None

##### *example usage*

```python
shakedown.add_user('billy', 'billy', 'billy the admin kid')
```


### get_user()

Returns a user from DCOS Enterprise.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**uid** | user id | str

##### *example usage*

```python
shakedown.get_user('billy')
```


### remove_user()

Removes a user from DCOS Enterprise.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**uid** | user id | str

##### *example usage*

```python
shakedown.remove_user('billy')
```


### ensure_resource()

Adds resource if not currently in the system.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**rid** | resource id | str

##### *example usage*

```python
shakedown.ensure_resource('dcos:service:marathon:marathon:services:/example-secure')
```


### set_user_permission()

Assigns access rights to user by uid for a resource by rid
rid, uid, action='full'

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**rid** | resource id | str
**uid** | user id | str
action | access right: read, write, update, delete or full | str | 'full'

##### *example usage*

```python
# provides full access for billy to resource
shakedown.set_user_permission('dcos:service:marathon:marathon:services:/example-secure', 'billy')
```


### remove_user_permission()

Removes user permissions for a resource.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**rid** | resource id | str
**uid** | user id | str
action | access right: read, write, update, delete or full | str | 'full'

##### *example usage*

```python
#  removes full access to resource for billy
shakedown.remove_user_permission('dcos:service:marathon:marathon:services:/example-secure', 'billy')
```


### add_group()

Adds a group to DCOS Enterprise

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**id** | group id | str
description | description of group | str | None


##### *example usage*

```python
shakedown.add_group('test-group')
```


### get_group()

Returns a group from DCOS Enterprise

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**id** | group id | str


##### *example usage*

```python
shakedown.get_group('test-group')
```


### remove_group()

Removes a group from DCOS Enterprise

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**id** | group id | str


##### *example usage*

```python
#  removes group
shakedown.remove_group('test-group')
```


### add_user_to_group()

Adds user to group

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**uid** | user id | str
**gid** | group id | str
exist_ok | True results in no error if user already belongs to group | bool | True

##### *example usage*

```python
shakedown.add_user_to_group('billy', 'test-group')
```


### remove_user_from_group()

Removes user from group

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**uid** | user id | str
**gid** | group id | str

##### *example usage*

```python
shakedown.remove_user_from_group('billy', 'test-group')
```


### credentials()

Context for credentials such that super user is returned after test

##### *parameters*

None.

##### *example usage*

```python
@pytest.mark.usefixtures('credentials')
def test_monkey_with_users():
```


### no_user()

Context with no logged in user.   Return to super user after context.

##### *parameters*

None.

##### *example usage*

```python
with shakedown.no_user():
  # do some action requiring no user auth
```


### new_dcos_user()

Context with a newly created and logged in user.   Return to super user after context.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**username** | the username used for DC/OS authentication | str
**password** | the password used for DC/OS authentication | str

##### *example usage*

```python
with shakedown.new_dcos_user('kenny', 'kenny'):
  # do some action for this user
```


### dcos_user()

Context with an existing user logged in.  Return to super user after context.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
**username** | the username used for DC/OS authentication | str
**password** | the password used for DC/OS authentication | str

##### *example usage*

```python
with shakedown.dcos_user('kenny', 'kenny'):
  # do some action for this user
```


### get_agents()

Retrieve a list of all agent node IP addresses.

##### *parameters*

None

##### *example usage*

```python
# What do I look like in IP space?
nodes = get_agents()
print("Node IP addresses: " + nodes)
```

### get_private_agents()

Retrieve a list of all private agent node IP addresses.

##### *parameters*

None

##### *example usage*

```python
# What do I look like in IP space?
private_nodes = get_private_agents()
print("Private IP addresses: " + private_nodes)
```


### get_public_agents()

Retrieve a list of all public agent node IP addresses.

##### *parameters*

None

##### *example usage*

```python
# What do I look like in IP space?
public_nodes = get_public_agents()
print("Public IP addresses: " + public_nodes)
```

### partition_agent()

Separates the agent from the cluster by adjusting IPTables with the following:

```
sudo iptables -F INPUT
sudo iptables -I INPUT -p tcp --dport 22 -j ACCEPT
sudo iptables -I INPUT -p icmp -j ACCEPT
sudo iptables -I OUTPUT -p tcp --sport 5051  -j REJECT
sudo iptables -A INPUT -j REJECT
```

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
hostname | the hostname or IP of the node | str

##### *example usage*

```python
# Partition all the public nodes
public_nodes = get_public_agents()
for public_node in public_nodes:
    partition_agent(public_node)
```

### reconnect_agent()

Reconnects a previously partitioned agent by reversing the IPTable changes.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
hostname | the hostname or IP of the node | str

##### *example usage*

```python
# Reconnect the public agents
for public_node in public_nodes:
    reconnect_agent(public_node)
```

### restart_agent()

Restarts an agent process at the host.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
hostname | the hostname or IP of the node | str

##### *example usage*

```python
# Reconnect the public agents
for public_node in public_nodes:
    restart_agent(public_node)
```

### stop_agent()

Stops an agent process at the host.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
hostname | the hostname or IP of the node | str

##### *example usage*

```python
# Reconnect the public agents
for public_node in public_nodes:
    stop_agent(public_node)
```

### start_agent()

Start an agent process at the host.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
hostname | the hostname or IP of the node | str

##### *example usage*

```python
# Reconnect the public agents
for public_node in public_nodes:
    start_agent(public_node)
```

### delete_agent_log()

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
hostname | the hostname or IP of the node | str

##### *example usage*

```python
# Delete agent logs on the public agents
for public_node in public_nodes:
    delete_agent_log(public_node)
```


### kill_process_on_host()

Kill the process(es) matching pattern at ip.  This will potentially kill infrastructure processes.
##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
hostname | the hostname or IP of the node | str
pattern  | A regular expression matching the name of the process to
kill | str

##### *example usage*

```python
# kill java on the public agents
for public_node in public_nodes:
    kill_process_on_host(public_node, "java")
```


### kill_process_from_pid_file_on_host()

Kill the process found in pid file on the host.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
hostname | the hostname or IP of the node | str
pid_file | name of file holding the PID on master | str | python_http.pid


##### *example usage*

```python
# starts http service on master at port 7777
pid_file = start_master_http_service()

# kill http service
kill_process_from_pid_file_on_host(shakedown.master_ip(), pid_file)
```


### disconnected_agent()

Managed context which will disconnect an agent for the duration of the context then restore the agent
##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
hostname | the hostname or IP of the node | str

##### *example usage*

```python
# disconnects agent
with disconnected_agent(host):
        service_delay()

# agent is reconnected
wait_for_service_url(PACKAGE_APP_ID)
```


### required_private_agents()

Function which returns True if the number of required agents is NOT present, otherwise
returns False.  The purpose of this function is to be used to determine if a test
would be skipped or not.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
count | required number of agents | int

##### *example usage*

```python
# if the DC/OS cluster has less than 2 private agents it will be skipped
# it will run with 2 or more agents.
@pytest.mark.skipif('required_private_agents(2)')
def test_fancy_multi_agent_check():
```


### required_public_agents()

Function which returns True if the number of required agents is NOT present, otherwise
returns False.  The purpose of this function is to be used to determine if a test
would be skipped or not.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
count | required number of agents | int

##### *example usage*

```python
# if the DC/OS cluster has less than 2 public agents it will be skipped
# it will run with 2 or more agents.
@pytest.mark.skipif('required_public_agents(2)')
def test_fancy_multi_agent_check():

```


### private_agents

Annotation decorator factory.  It requires the import of required_private_agents
in order to function.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
count | required number of private agents | int | 1


##### *example usage*

```python
# if the DC/OS cluster has less than 1 private agents it will be skipped
@private_agents(1)
def test_fancy_multi_agent_check():
```


### public_agents

Annotation decorator factory.  It requires the import of required_public_agents
in order to function.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
count | required number of public agents | int | 1


##### *example usage*

```python
# if the DC/OS cluster has less than 1 public agents it will be skipped
@public_agents(1)
def test_fancy_multi_agent_check():
```


### disconnected_master()

Managed context which will disconnect the master for the duration of the context then restore the master
##### *parameters*

None

##### *example usage*

```python
# disconnects agent
with disconnected_master(host):
        service_delay()

# master is reconnected
wait_for_service_url(PACKAGE_APP_ID)
```


### wait_for_mesos_endpoint()

Checks the mesos url returns HTTP 200 within a timeout if available it returns true on expiration it returns false.

##### *parameters*

None

##### *example usage*

```python
# disconnect master
restart_master_node()

# master is reconnected
wait_for_mesos_endpoint()
```

### get_all_masters()

Provides a list of all masters in the cluster

##### *parameters*

None

##### *example usage*

```python
for master in get_all_masters():
  # do master like things
```


### master_leader_ip()

Provides the IP of the master leader.  This is the internal IP of leader.

##### *parameters*

None

##### *example usage*

```python
ip = master_leader_ip()
```


### get_all_master_ips()

Provides a list of all the IP address for the masters

##### *parameters*

None

##### *example usage*

```python
for ip in get_all_master_ips():
```

### start_master_http_service()

Starts a http service on the master leader.  The main purpose is to serve up artifacts for launched test applications.   This is commonly used in combination with copying tests or artifacts to the leader
than configuring the messos task to fetch from http://master.mesos:7777/artifact.tar

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
port | the port to use for http | int | 7777
pid_file | the file to save the pid to | str | python_http.pid

##### *example usage*

```python
# starts http service on master at port 7777
start_master_http_service()
```

### master_http_service()

Managed context which will start the http service and will kill the process when the
context is over.  It calls `start_master_http_service` before context and
`kill_process_from_pid_file_on_master` at the end of the context.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
port | the port to use for http | int | 7777


##### *example usage*

```python
copy_file_to_master('foo.tar')
# create http service
with master_http_service():
    # create an app that fetches from http://master.mesos:7777/foo.tar
    # verify task
# http is gone
```


### iptable_rules()

Managed context which will save the firewall rules then restore them at the end of the context for the host.
It calls `save_iptables` before the context and `restore_iptables` and the end of the context.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
hostname | the hostname or IP of the node | str


##### *example usage*

```python
# disconnects agent
with iptable_rules(shakedown.master_ip()):
    block_port(host, port)
    time.sleep(7)

# firewalls restored
wait_for_service_url(PACKAGE_APP_ID)
```

### restore_iptables()

Reverses and restores saved iptable rules.  It works with `save_iptables`.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
hostname | the hostname or IP of the node | str


##### *example usage*

```python
# disconnects agent
restore_iptables(host)
```

### save_iptables()

Saves the current iptables to a file on the host.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
hostname | the hostname or IP of the node | str


##### *example usage*

```python
# disconnects agent
save_iptables(host)
```


### flush_all_rules()

Flushes the iptables rules for the host.  `sudo iptables -F INPUT`.   Consider using `save_iptables` prior to use.

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
hostname | the hostname or IP of the node | str


##### *example usage*

```python
# disconnects agent
flush_all_rules(host)
```

### allow_all_traffic()

Removes iptable rules allow full access.  Consider using `save_iptables` prior to using.
sudo iptables --policy INPUT ACCEPT && sudo iptables --policy OUTPUT ACCEPT && sudo iptables --policy FORWARD ACCEPT'

##### *parameters*

parameter | description | type | default
--------- | ----------- | ---- | -------
hostname | the hostname or IP of the node | str


##### *example usage*

```python
# disconnects agent
allow_all_traffic(host)
```
