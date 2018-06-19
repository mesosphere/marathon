## 1.5.0 (2018-03-13)

Fixes:

  - improved SSH session-handling

## 1.4.12 (2017-12-20)

Features:

  - support for DC/OS 1.11 (`@dcos_1_11` PyTest marker)

Fixes:

  - don't flake if a connection error occurs during an install

## 1.4.11 (2017-12-08)

Features:

  - new function to retrieve available versions of packages
    - `get_package_versions()`
  - bumped `dcoscli` version to `0.5.7`

Fixes:

  - handle `skip` state when printing output

## 1.4.10 (2017-12-06)

Features:

  - new leader-based run functions
    - `run_command_on_leader()`
    - `run_command_on_marathon_leader()`
  - log the resulting package version when installing from `latest`

## 1.4.9 (2017-10-18)

Features:

  - added API functions to query for Docker version
    - `docker_version()`
    - `docker_client_version()`
    - `docker_server_version()`
  - added classifiers to the setup to specify the programming langauge to
    be used with pipenv

Fixes:

  - removed staticly-set assertions in `test_get_reserved_resources()`

## 1.4.8 (2017-09-14)

Features:

  - all CLI options now have the option to be specified as environmental
    variables, eg. `SHAKEDOWN_DCOS_URL`, `SHAKDOWN_USER`, etc.

Fixes:

  - CLI subcommand (non-app) packages now treated the same as app/service
    packages
    - `install_package()` and `uninstall_package()` won't throw exceptions
    - `package_installed()` returns `True` for non-app packages

## 1.4.7 (2017-09-01)

Features:

  - bumped `dcoscli` version to `0.5.5`

## 1.4.6 (2017-08-23)

Features:

  - bumped `dcoscli` version to `0.5.4`
  - better attach error handling
  - move from pip to pip3

## 1.4.5 (2017-07-13)

Features:

  - bumped `dcoscli` version to `0.5.3`

## 1.4.4 (2017-06-19)

Fixes:

  - use `timeout_sec` in `install_package()`
  - return service name in `_get_service_name()`
  - `wait_for()` should use `>=`, not `>`

## 1.4.3 (2017-06-05)

Fixes:

  - properly timeout with `time_wait()` in `wait_for_task_completion()`
  - `time_wait` was not being imported in `dcos/master.py`
  - use `get_tasks()` in `task_completed()`

## 1.4.2 (2017-05-09)

Features:

  - new methods for deploying Docker credentials
    - `create_docker_credentials_file()`
    - `distribute_docker_credentials_to_private_agents()`
    - `prefetch_docker_image_on_private_agents()`
  - added ability to query reservations
    - `get_resources_by_role()`

Fixes:

  - removed redundant filter in `get_task()`

## 1.4.1 (2017-04-26)

Fixes:

  - fix partitioning bug (QUALITY-1432), use new network APIs rather than
    SSH-based command execution

## 1.4.0 (2017-04-25)

Features:

  - new security methods:
    - `ensure_resource()`
    - `add_user()`, `get_user()`, `remove_user()`
    - `set_user_permission()`
    - `add_group()`, `get_group()`, and `remove_group()`
    - `add_user_to_group()`, `remove_user_from_group()`
  - new security contexts:
    - `credentials()`
    - `no_user()`
    - `new_dcos_user()`
    - `dcos_user()`
  - `wait_while_exceptions()` spinner
  - new contexts available for Marathon-on-Marathon (MoM) deployments
    - `mom_version()`
    - `mom_version_less_than()`
    - `marathon_on_marathon()`

Fixes:

  - Shakedown now functions without authentication options on clusters not
    requiring authentication

## 1.3.4 (2017-04-20)

Features:

  - `wait_for` waits for service endpoints to be debounced
    successfully in a specified number of masters in multi-master
    setups
  - new PyTest decorators methods for determining Shakedown versions
    - `shakedown_canonical_version()`
    - `shakedown_version_less_than()`
  - new methods for determining leaders in multi-master setups
    - `marathon_leader_ip()`
    - `master_leader_ip()`

Fixes:

  - fixed lack zookeeper-related methods failing in methods running
    commands on masters
  - fixup for `kill_process_from_pid_file_on_host()` which only killed
    processes on master

## 1.3.3 (2017-04-13)

Features:

  - ability to start a HTTP server running on the master via
    `start_master_http_service()` method, along with an associated
    `master_http_service()` context
  - new methods for killing processes based on pidfiles
    - `kill_process_from_pid_file_on_host()`
    - `kill_process_from_pid_file_on_master()`

## 1.3.2 (2017-04-05)

Fixes:

  - include `partition_cmd` file in package distribution
  - fixup for `test_install_package_with_subcommand` test
  - corrected `dcos_agents_state()` example usage docs
  - `wait_for` now aborts when encountering exception chains

## 1.3.1 (2017-03-09)

Fixes:

  - `@private_agents()` and `@public_agents()` bug in `1.3.0`

## 1.3.0 (2017-03-09)

Features:

  - `--stdout-inline` now passes the `--capture=none` (`-s`) flag to
    PyTest, enabling streaming inline test output
  - `@private_agents()` and `@public_agents()` PyTest decorators to
    replace old explicitly-named (eg. `@private_agent_2`) decorators
  - `wait_for_mesos_endpoint()` method waits for a specific endpoint
    to return `HTTP 200`
  - new ZooKeeper method `get_zk_node_children()`
  - new master methods `get_all_masters()` and `get_all_master_ips()`
    for clusters deployed with multiple master nodes

Fixes:

  - updated `test_dcos_command` test to work on new release of CoreOS

## 1.2.2 (2017-03-01)

Features:

  - stringified `wait_for` predicate info for better debugging and
    understanding of which predicate and which values

Fixes:

  - trailing slash added for `/service/{}/` calls
  - turn off noisy option in `wait_for`
  - documented how to use `DCOS_CONFIG_ENV` for parallel execution
  - `wait_for` log previously ignored exception during waiting when noisy
    enabled

## 1.2.1 (2017-02-24)

Features:

  - DC/OS Enterprise Edition version-checking
    - `ee_version()` method, `@strict`, `@permissive`, and `@disabled`
      PyTest security mode markers
  - bumped `dcoscli` version to `0.4.16`

## 1.2.0 (2017-02-17)

Features:

  - ability to skip tests based on required resources, DC/OS versioning
    - eg. `@pytest.mark.skipif('required_cpus(2)')` and `@dcos_1_9`
  - support for deleting leftover data following persistent service
    uninstall (ie.a `janitor.py` shim)
      - `delete_persistent_data()`
      - `destroy_volume()` / `destroy_volumes()`
      - `unreserve_resources()` / `unreserve_resources()`
  - command methods now allow disabling output and/or raising on error
  - marathon methods add `delete_app`/`delete_app_wait` for a specific app
  - package install methods support waiting for service tasks to start
    running, additional logging
  - package uninstall methods add support for deleting persistent volumes
    after uninstall
  - packge repo methods support waiting for add/remove repo to complete,
    check for a changed package version
  - service method function for deleting persistent volumes
  - service method support waiting for task rollout to complete
  - service method verifying that tasks are not changed

Fixes:

  - marathon methods fix unused `app_id` when checking deployment
  - `hello-world` package (previously `riak`) now used to test
    `test_install_package_with_subcommand()`
  - spinner-related fixes:
    - while a spinner is polling, print the spin time and any
      ignored exceptions by default
    - don't drop the original stack when rethrowing exceptions
    - return the result of the spin at the end of `wait_for`,
      allowing passthrough of the predicate return value

## 1.1.15 (2017-02-03)

Features:

  - split `requirements.txt` and `requirements-edge.txt` for
    building against `dcoscli:master`

Fixes:

  - `_wait()` functions now wait on deployment, not health
  - uninstalls now wait for Mesos task removal
  - tests fixed for package installation and waiting
  - improved error messaging when unable to connect to a host

## 1.1.14 (2017-01-13)

Features:

  - new cluster functions `get_resources`, `resources_needed`,
    `get_used_resources`, `get_unreserved_resources`,
    `get_reserved_resources`, and `available_resources`

## 1.1.13 (2017-01-05)

Features:

  - bumped `dcoscli` version to `0.4.15`

Fixes:

  - `timeout_sec` passed through to `time_wait()` method

## 1.1.12 (2016-12-27)

Features:

  - SSH user (default `core`) can be specified on the command line
    with `--ssh-user`
  - new ZooKeeper `get_zk_node_data` function`

Fixes:

  - `test_install_package_with_json_options` test is set to `xfail`
    while it is being diagnosed

## 1.1.11 (2016-12-13)

Features:

  - test timeouts as defined by `--timeout` (or `timeout` in
    `.shakedown` config) or `@pytest.mark.timeout(n)` for
    individual tests

Fixes:

  - exit code is now returned for `run_dcos_command` calls
  - fallbaack to `ssh-agent` if `.ssh/id_rsa` key fails
  - `wait` predicate fixed from `1.1.9`
  - use `service_name` rather than former ambiguous `app_id`

## 1.1.10 (2016-11-08)

Fixes:

  - fixing broken PyPI 1.1.9 release

## 1.1.9 (2016-11-08)

Features:

  - 'wait' functions and predicates, including `wait_for_task`,
    `wait_for_task_property`, `wait_for_dns`, and `deployment_wait`
  - new marathon methods for `deployment_wait`, `delete_all_apps`,
    and `delete_all_apps_wait`
  - support for passing a dict object containing JSON options to
    `install_package` methods
  - bumped `dcoscli` version to `0.4.14`

Fixes:

  - pep8-compliance

## 1.1.8 (2016-11-01)

Features:

  - `--dcos-url` now defaults to pre-configured dcos-cli value
  - `dcos_dns_lookup` method for resolving Mesos-DNS queries
  - reworked network paritioning methods, including new utility
    methods for `disconnected_agent`, `disconnected_master`,
    `save_iptables`, `flush_all_rules`, `allow_all_traffic`, and
    `iptable_rules`
  - added `tox.ini` configuration file

Fixes:

  - fixed documentation for `get_private_agents()`

## 1.1.7 (2016-10-20)

Features:

  - `--fail` now defaults to `never`
  - the `run_command` and associated agent/master methods now return
    both the exit status and captured output

Fixes:

  - `kill_process_on_host` method now working, better output
  - improved `--stdout-inline` readability
  - fixed bug where output from teardown methods did not display if
    module only had a single test

## 1.1.6 (2016-10-14)

Features:

  - `--username`, `--password`, and `--oauth-token` can now be
    specified via the command line
  - updated documentation on installing via PyPI or with virtualenv

Fixes:

  - authentication methods (OAuth, user/pass) are only attempted
    when the related options are defined
  - removed `pytest_runtest_makereport` method which was unused and
    causing pytest warning messages on every run

## 1.1.5 (2016-10-12)

Fixes:

  - cluster authentication attempts are sequenced (existing ACS token ->
    OAuth token -> username/password combo)
  - output from module setup and teardown is now printed to stdout
  - multiple test files (or multiple tests) can be again be specified
    (broken in `1.1.4`)


## 1.1.4 (2016-10-07)

Features:

  - allow a session to be authenticated using a supplied OAuth token
    in `.shakedown` (`oauth_token = <token>`)
  - new `delete_agent_log` method to delete logs on agents
  - pytest-style single-test specification (`test_file.py::test_name`)

Fixes:

  - `dcos-shakedown` command now functions correctly from cmd


## 1.1.3 (2016-10-04)

Features:

  - bumped `dcoscli` version to `0.4.13`
  - modified CLI 'short' flags to match SSH/curl

Fixes:

  - removed superfluous `key not found` error message when SSH
    key could not be located


## 1.1.2 (2016-09-21)

Features:

  - new `--pytest-option` flag to pass CLI options on to pytest
  - short (single-character) flags for commonly-used CLI options

Fixes:

  - improved `--help` CLI output


## 1.1.1 (2016-09-20)

Initial implementation of changelog.

Features:

  - new `partition_master` and `reconnect_master` methods

Fixes:

  - added missing API documentation for `authenticate` method
  - updated PyPI packaging name to `dcos-shakedown`
  - renamed `task_name` to `task_id` in task methods
  - check the exit code of the command in `run_command` method`
