from utils import *
from common import *

import csv
import time
import sys
import os
"""
    assumptions:
        1) written in progressively higher scale
        2) mom1 and mom2 are group such that we are not switching each test
        3) the tests are run in the order they are written
"""
default_moms = {
    'mom1': '1.3.6',
    'mom2': '1.4.0-RC4'
}
# to be discovered
marathons = {}
type_test_failed = {}

test_log = []
##############
# Test Section
##############
# MOM1


def test_mom1_apps_instances_1_1():
    run_test(sys._getframe().f_code.co_name)


def test_mom1_apps_instances_1_10():
    run_test(sys._getframe().f_code.co_name)


def test_mom1_apps_instances_1_100():
    run_test(sys._getframe().f_code.co_name)


def test_mom1_apps_instances_1_500():
    run_test(sys._getframe().f_code.co_name)


def test_mom1_apps_instances_1_1000():
    run_test(sys._getframe().f_code.co_name)


def test_mom1_apps_instances_1_5000():
    run_test(sys._getframe().f_code.co_name)


def test_mom1_apps_instances_1_10000():
    run_test(sys._getframe().f_code.co_name)


def test_mom1_apps_instances_1_25000():
    run_test(sys._getframe().f_code.co_name)


#  counts
def test_mom1_apps_count_1_1():
    run_test(sys._getframe().f_code.co_name)


def test_mom1_apps_count_10_1():
    run_test(sys._getframe().f_code.co_name)


def test_mom1_apps_count_100_1():
    run_test(sys._getframe().f_code.co_name)


def test_mom1_apps_count_500_1():
    run_test(sys._getframe().f_code.co_name)


def test_mom1_apps_count_1000_1():
    run_test(sys._getframe().f_code.co_name)


def test_mom1_apps_count_5000_1():
    run_test(sys._getframe().f_code.co_name)


def test_mom1_apps_count_10000_1():
    run_test(sys._getframe().f_code.co_name)


def test_mom1_apps_count_25000_1():
    run_test(sys._getframe().f_code.co_name)


# group
def test_mom1_apps_group_1_1():
    run_test(sys._getframe().f_code.co_name)


def test_mom1_apps_group_10_1():
    run_test(sys._getframe().f_code.co_name)


def test_mom1_apps_group_100_1():
    run_test(sys._getframe().f_code.co_name)


def test_mom1_apps_group_1000_1():
    run_test(sys._getframe().f_code.co_name)


# MOM2
def test_mom2_apps_instances_1_1():
    run_test(sys._getframe().f_code.co_name)


def test_mom2_apps_instances_1_10():
    run_test(sys._getframe().f_code.co_name)


def test_mom2_apps_instances_1_100():
    run_test(sys._getframe().f_code.co_name)


def test_mom2_apps_instances_1_500():
    run_test(sys._getframe().f_code.co_name)


def test_mom2_apps_instances_1_1000():
    run_test(sys._getframe().f_code.co_name)


def test_mom2_apps_instances_1_5000():
    run_test(sys._getframe().f_code.co_name)


def test_mom2_apps_instances_1_10000():
    run_test(sys._getframe().f_code.co_name)


def test_mom2_apps_instances_1_25000():
    run_test(sys._getframe().f_code.co_name)


# counts
def test_mom2_apps_count_1_1():
    run_test(sys._getframe().f_code.co_name)


def test_mom2_apps_count_10_1():
    run_test(sys._getframe().f_code.co_name)


def test_mom2_apps_count_100_1():
    run_test(sys._getframe().f_code.co_name)


def test_mom2_apps_count_500_1():
    run_test(sys._getframe().f_code.co_name)


def test_mom2_apps_count_1000_1():
    run_test(sys._getframe().f_code.co_name)


def test_mom2_apps_count_5000_1():
    run_test(sys._getframe().f_code.co_name)


def test_mom2_apps_count_10000_1():
    run_test(sys._getframe().f_code.co_name)


def test_mom2_apps_count_25000_1():
    run_test(sys._getframe().f_code.co_name)


#  group
def test_mom2_apps_group_1_1():
    run_test(sys._getframe().f_code.co_name)


def test_mom2_apps_group_10_1():
    run_test(sys._getframe().f_code.co_name)


def test_mom2_apps_group_100_1():
    run_test(sys._getframe().f_code.co_name)


def test_mom2_apps_group_1000_1():
    run_test(sys._getframe().f_code.co_name)
##############
# End Test Section
##############


def run_test(name):
    current_test = start_test(name, marathons)
    test_log.append(current_test)
    need = scaletest_resources(current_test)
    # TODO: why marathon stops at 80%
    if need > (available_resources() * 0.8):
        current_test.skip('insufficient resources')
        return
    if previous_style_test_failed(current_test):
        current_test.skip('smaller scale failed')
        return

    assert ensure_test_mom(current_test)
    # wait for max
    # respond to timeouts
    with marathon_on_marathon():
        time = scale_test_apps(current_test)

    if "failed" in current_test.status:
        type_test_failed[get_mom_style_key(current_test)] = True


def get_mom_style_key(current_test):
    return '{}_{}'.format(current_test.mom, current_test.style)


def previous_style_test_failed(test_obj):
    failed = False
    try:
        failed = type_test_failed.get(get_mom_style_key(current_test))
    except:
        failed = False
        pass

    return failed


def set_mom(name):
    try:
        marathons[name] = os.environ[name.upper()]
    except:
        marathons[name] = default_moms[name]
        pass


def setup_module(module):
    set_mom('mom1')
    set_mom('mom2')
    cluster_info()
    print('marathons in test: {}'.format(marathons))
    print(available_resources())


def teardown_module(module):
    stats = collect_stats()
    write_csv(stats)
    read_csv()
    write_meta_data(get_metadata())


def get_metadata():
    metadata = {
        'marathons': marathons
    }
    return metadata


def collect_stats():
    stats = {
        'mom1_instances': [],
        'mom1_instances_target': [],
        'mom1_instances_max': [],
        'mom1_count': [],
        'mom1_count_target': [],
        'mom1_count_max': [],
        'mom1_group': [],
        'mom1_group_target': [],
        'mom2_group': [],
        'mom2_group_target': [],
        'mom2_instances': [],
        'mom2_instances_target': [],
        'mom2_instances_max': [],
        'mom2_count': [],
        'mom2_count_target': [],
        'mom2_count_max': []
    }

    for scale_test in test_log:
        print(scale_test)
        scale_test.log_events()
        scale_test.log_stats()
        print('')
        stats.get(get_mom_style_key(scale_test)).append(scale_test.deploy_time)
        target_key = '{}_target'.format(get_mom_style_key(scale_test))
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
        write_stat_lines(f, w, stats, 'mom1', 'instances')
        write_stat_lines(f, w, stats, 'mom2', 'instances')
        write_stat_lines(f, w, stats, 'mom1', 'count')
        write_stat_lines(f, w, stats, 'mom2', 'count')
        write_stat_lines(f, w, stats, 'mom1', 'group')
        write_stat_lines(f, w, stats, 'mom2', 'group')


def write_stat_lines(f, w, stats, marathon, test_type):
        f.write('Marathon: {}, {}'.format(marathons.get(marathon), test_type))
        f.write('\n')
        w.writerow(stats['{}_{}_target'.format(marathon, test_type)])
        w.writerow(stats['{}_{}'.format(marathon, test_type)])
        f.write('\n')


def get_current_test():
    return test_log[-1]
