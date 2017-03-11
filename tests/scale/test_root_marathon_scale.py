from utils import *
from common import *

import pytest

import csv
import time
import sys
import os
"""
    assumptions:
        1) written in progressively higher scale
"""


type_test_failed = {}

test_log = []
##############
# Test Section
##############


@pytest.mark.parametrize("num_apps, num_instances", [
  (1, 1),
  (1, 10),
  (1, 100),
  (1, 500),
  (1, 1000),
  (1, 5000),
  (1, 10000),
  (1, 25000),
  (1, 50000)
])
def test_instance_scale(num_apps, num_instances):
    """ Runs scale tests on `num_instances` of usually 1 app.
    """

    current_test = initalize_test('root', 'apps', 'instances', num_apps, num_instances)
    instance_test_app(current_test)
    log_current_test(current_test)


@pytest.mark.parametrize("num_apps, num_instances", [
  (1, 1),
  (10, 1),
  (100, 1),
  (500, 1),
  (1000, 1),
  (5000, 1),
  (10000, 1),
  (25000, 1),
  (50000, 1)
])
def test_count_scale(num_apps, num_instances):
    """ Runs scale test on `num_apps` usually 1 instance each.
    """
    current_test = initalize_test('root', 'apps', 'count', num_apps, num_instances)
    count_test_app(current_test)
    log_current_test(current_test)


@pytest.mark.parametrize("num_apps, num_instances", [
  (1, 1),
  (10, 1),
  (100, 1),
  (500, 1),
  (1000, 1),
  (5000, 1),
  (10000, 1),
  (25000, 1),
  (50000, 1)
])
def test_group_scale(num_apps, num_instances):
    """ Runs scale test on `num_apps` usually 1 instance each deploy as a group.
    """

    current_test = initalize_test('root', 'apps', 'group', num_apps, num_instances)
    group_test_app(current_test)
    log_current_test(current_test)


##############
# End Test Section
##############


def initalize_test(marathon_name='root', under_test='apps', style='instances', num_apps=1, num_instances=1):

    current_test = create_test_object(marathon_name, under_test, style, num_apps, num_instances)
    test_log.append(current_test)
    need = scaletest_resources(current_test)

    # if need >= (private_resources_available()):
    if not has_enough_resources(need):
        current_test.skip('insufficient resources')

    if previous_style_test_failed(current_test):
        current_test.skip('smaller scale failed')

    if current_test.skipped:
        pytest.skip()

    return current_test


def has_enough_resources(need):
    """ this is temporary until shakedown PR 121 is merged
    """
    available = private_resources_available()
    return need.cpus <= available.cpus and need.mem <= available.mem


def get_test_style_key_base(current_test):
    """ The style key is historical and is the key to recording test results.
    For root marathon the key is `root_instances` or `root_group`.
    """
    return get_style_key_base(current_test.mom, current_test.style)


def get_test_key(current_test, key):
    return get_key(current_test.mom, current_test.style, key)


def get_style_key_base(marathon_name, style):
    return '{}_{}'.format(marathon_name, style)


def get_key(marathon_name, style, key):
    return "{}_{}".format(get_style_key_base(marathon_name, style), key)


def previous_style_test_failed(current_test):
    return type_test_failed.get(get_test_style_key_base(current_test), False)


def setup_module(module):
    delete_all_apps_wait()
    cluster_info()
    print('testing root marathon')
    print("private resources: {}".format(private_resources_available()))


def teardown_module(module):
    stats = collect_stats()
    write_csv(stats)
    read_csv()
    write_meta_data(get_metadata())

    try:
        delete_all_apps_wait()
    except:
        pass


def get_metadata():
    version = None

    try:
        ee_version()
    except:
        pass

    metadata = {
        'marathon': 'root'
    }

    if version is not None:
        metadata['security'] = version

    return metadata


def log_current_test(current_test):
    if "failed" in current_test.status:
        type_test_failed[get_test_style_key_base(current_test)] = True

    print(current_test)
    current_test.log_events()
    current_test.log_stats()
    print('')


def collect_stats():
    stats = {
        'root_instances_target': [],
        'root_instances_max': [],
        'root_instances_deploy_time': [],
        'root_instances_human_deploy_time': [],
        'root_instances_launch_status': [],
        'root_instances_deployment_status': [],
        'root_count_target': [],
        'root_count_max': [],
        'root_count_deploy_time': [],
        'root_count_human_deploy_time': [],
        'root_count_launch_status': [],
        'root_count_deployment_status': [],
        'root_group_target': [],
        'root_group_max': [],
        'root_group_deploy_time': [],
        'root_group_human_deploy_time': [],
        'root_group_launch_status': [],
        'root_group_deployment_status': []
    }

    for scale_test in test_log:
        print(scale_test)
        scale_test.log_events()
        scale_test.log_stats()
        print('')

        key = get_test_key(scale_test, 'target')
        stats.get(key).append(scale_test.target)

        key = get_test_key(scale_test, 'max')
        stats.get(key).append(scale_test.deploy_results.current_scale)

        key = get_test_key(scale_test, 'deploy_time')
        stats.get(key).append(scale_test.test_time)

        key = get_test_key(scale_test, 'human_deploy_time')
        stats.get(key).append(pretty_duration_safe(scale_test.test_time))

        key = get_test_key(scale_test, 'launch_status')
        stats.get(key).append(pass_status(scale_test, scale_test.launch_results.success))

        key = get_test_key(scale_test, 'deployment_status')
        stats.get(key).append(pass_status(scale_test, scale_test.deploy_results.success))

    return stats


def pass_status(test, successful):
    if test.skipped:
        return 's'
    if successful:
        return 'p'
    else:
        return 'f'


def read_csv(filename='scale-test.csv'):
    with open(filename, 'r') as fin:
        print(fin.read())


def write_csv(stats, filename='scale-test.csv'):
    with open(filename, 'w') as f:
        w = csv.writer(f, quoting=csv.QUOTE_NONNUMERIC)
        write_stat_lines(f, w, stats, 'root', 'instances')
        write_stat_lines(f, w, stats, 'root', 'count')
        write_stat_lines(f, w, stats, 'root', 'group')


def write_stat_lines(f, w, stats, marathon_name, test_type):
        f.write('Marathon: {}, {}'.format('root', test_type))
        f.write('\n')
        w.writerow(stats[get_key(marathon_name, test_type, 'target')])
        w.writerow(stats[get_key(marathon_name, test_type, 'max')])
        w.writerow(stats[get_key(marathon_name, test_type, 'deploy_time')])
        w.writerow(stats[get_key(marathon_name, test_type, 'human_deploy_time')])
        w.writerow(stats[get_key(marathon_name, test_type, 'launch_status')])
        w.writerow(stats[get_key(marathon_name, test_type, 'deployment_status')])
        f.write('\n')


def get_current_test():
    return test_log[-1]
