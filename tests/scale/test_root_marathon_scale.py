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
  (1, 25000)
])
def test_instance_scale(num_apps, num_instances):
    """ Runs scale tests on `num_instances` of usually 1 app.
    """
    run_test('root', 'apps', 'instances', num_apps, num_instances)


@pytest.mark.parametrize("num_apps, num_instances", [
  (1, 1),
  (10, 1),
  (100, 1),
  (500, 1),
  (1000, 1),
  (5000, 1),
  (10000, 1),
  (25000, 1)
])
def test_count_scale(num_apps, num_instances):
    """ Runs scale test on `num_apps` usually 1 instance each.
    """
    run_test('root', 'apps', 'count', num_apps, num_instances)


@pytest.mark.parametrize("num_apps, num_instances", [
  (1, 1),
  (10, 1),
  (100, 1),
  (500, 1),
  (1000, 1)
])
def test_group_scale(num_apps, num_instances):
    """ Runs scale test on `num_apps` usually 1 instance each deploy as a group.
    """
    run_test('root', 'apps', 'group', num_apps, num_instances)


##############
# End Test Section
##############


def run_test(marathon, launch_type, test_type, num_apps, num_instances):
    test_name = 'test_{}_{}_{}_{}_{}'.format(marathon, launch_type, test_type, num_apps, num_instances)
    current_test = start_test(test_name)
    test_log.append(current_test)
    need = scaletest_resources(current_test)
    # TODO: why marathon stops at 80%
    if need > (available_resources() * 0.8):
        current_test.skip('insufficient resources')
        return
    if previous_style_test_failed(current_test):
        current_test.skip('smaller scale failed')
        return

    # wait for max
    # respond to timeouts
    time = scale_test_apps(current_test)

    if "failed" in current_test.status:
        type_test_failed[get_style_key(current_test)] = True


def get_style_key(current_test):
    """ The style key is historical and is the key to recording test results.
    For root marathon the key is `root_instances` or `root_group`.
    """
    return '{}_{}'.format(current_test.mom, current_test.style)


def previous_style_test_failed(current_test):
    return type_test_failed.get(get_style_key(current_test), False)

def setup_module(module):
    delete_all_apps_wait()
    cluster_info()
    print('testing root marathon')
    print(available_resources())


def teardown_module(module):
    stats = collect_stats()
    write_csv(stats)
    read_csv()
    write_meta_data(get_metadata())
    delete_all_apps_wait()


def get_metadata():
    metadata = {
        'marathon': 'root'
    }
    return metadata


def collect_stats():
    stats = {
        'root_instances': [],
        'root_instances_target': [],
        'root_instances_max': [],
        'root_count': [],
        'root_count_target': [],
        'root_count_max': [],
        'root_group': [],
        'root_group_target': []
    }

    for scale_test in test_log:
        print(scale_test)
        scale_test.log_events()
        scale_test.log_stats()
        print('')
        stats.get(get_style_key(scale_test)).append(scale_test.deploy_time)
        target_key = '{}_target'.format(get_style_key(scale_test))
        if 'instances' in target_key:
            stats.get(target_key).append(scale_test.instance)
        else:
            stats.get(target_key).append(scale_test.count)

    return stats


def read_csv(filename='scale-test.csv'):
    with open(filename, 'r') as fin:
        print(fin.read())


def write_csv(stats, filename='scale-test.csv'):
    with open(filename, 'w') as f:
        w = csv.writer(f, quoting=csv.QUOTE_NONNUMERIC)
        write_stat_lines(f, w, stats, 'root', 'instances')
        write_stat_lines(f, w, stats, 'root', 'count')
        write_stat_lines(f, w, stats, 'root', 'group')


def write_stat_lines(f, w, stats, marathon, test_type):
        f.write('Marathon: {}, {}'.format('root', test_type))
        f.write('\n')
        w.writerow(stats['{}_{}_target'.format(marathon, test_type)])
        w.writerow(stats['{}_{}'.format(marathon, test_type)])
        f.write('\n')


def get_current_test():
    return test_log[-1]
