#!/usr/bin/env python

import click
import csv
import json
import math
import matplotlib.pyplot as plt
import numpy as np
import sys

from dcos.errors import DCOSException

from common import get_key, empty_stats
"""
    Graph functions for scale graphs.
    Prints 1up and 2up graphs of scale timings and errors.

    If you are running this on a Mac, you will likely need to create a ~/.matplotlib/matplotlibrc
    file and include `backend: TkAgg`.
    http://stackoverflow.com/questions/21784641/installation-issue-with-matplotlib-python
"""


def index_of_first_failure(stats, marathon_type, test_type):
    """ Finds the first occurance of an error during a deployment
    """
    index = -1
    deploy_status = stats.get(get_key(marathon_type, test_type, 'deployment_status'))
    for status in deploy_status:
        index += 1
        if "f" == status:
            return index

    return -1


def pad(array, size):
    current_size = len(array)
    if current_size < size:
        pad = np.zeros(size - current_size)
        padded = array.tolist() + pad.tolist()
        return np.array(padded)
    else:
        return array


def plot_test_timing(plot, stats, marathon_type, test_type, xticks):
    """ Plots a specific test graph.
        In addition, it sets the legend title, and flags the highest scale reached.

        :param plot: The matplotlib subplot object is the object that will be plotted
        :type plot: matplotlib subplot
        :param stats: This map contains the data to be plotted
        :type stats: map
        :param marathon_type: The type of marathon is part of the map key.  For scale tests it is `root` (vs. mom1)
        :type marathon_type: str
        :param test_type: Defines the test type, usually {instances, count, group}
        :type test_type: str
        :param xticks: An array of scale targets (1, 10, 100) for the x axis of the plot
        :type xticks: array

    """
    deploy_time = stats.get(get_key(marathon_type, test_type, 'deploy_time'))

    if deploy_time is None or len(deploy_time) == 0 or deploy_time[0] <= 0.0:
        return

    timings = np.array(deploy_time)
    title = '{} Scale Times'.format(test_type.title())
    timings = pad(timings, len(xticks))
    timings_handle, = plot.plot(xticks, timings, label=title)

    fail_index = index_of_first_failure(stats, marathon_type, test_type)
    if fail_index > 0:
        scale_at_fail = stats.get(get_key(marathon_type, test_type, 'max'))[fail_index]
        time_at_fail = stats.get(get_key(marathon_type, test_type, 'human_deploy_time'))[fail_index]
        text = '{} at {}'.format(scale_at_fail, time_at_fail)
        plot.text(fail_index, timings[fail_index], text,  wrap=True)


class GraphException(DCOSException):
    """ Raised when there is a issue with the ability to graph
    """

    def __init__(self, message):
        self.message = message


def plot_test_errors(plot, stats, marathon_type, test_type, xticks):
    """ Plots the number of errors for a given test

        :param plot: The matplotlib subplot object is the object that will be plotted
        :type plot: matplotlib subplot
        :param stats: This map contains the data to be plotted
        :type stats: map
        :param marathon_type: The type of marathon is part of the map key.  For scale tests it is `root` (vs. mom1)
        :type marathon_type: str
        :param test_type: Defines the test type, usually {instances, count, group}
        :type test_type: str
        :param xticks: An array of scale targets (1, 10, 100) for the x axis of the plot
        :type xticks: array

    """
    test_errors = stats.get(get_key(marathon_type, test_type, 'errors'))
    if test_errors is None or len(test_errors) == 0:
        return 0

    plot.set_title("Errors During Test")
    errors = np.array(test_errors)
    title = '{} Errors'.format(test_type.title())
    errors = pad(errors, len(errors))
    errors_handle, = plot.plot(xticks, errors, label=title, marker='o', linestyle='None')
    return max(test_errors)


def create_scale_graph(stats, metadata, file_name='scale.png'):
    """ Creates a 1up or 2up scale graph depending on if error information is provided.
        The first 1up graph "time_plot", is x = scale and y = time to reach scale
        The second graph "error_plot", is an error graph that plots the number of errors that occurred during the test.

        :param stats: This map contains the data to be plotted
        :type stats: map
        :param metadata: The JSON object that contains the metadata for the cluster under test
        :type metadata: JSON
        :param file_name: The file name of the graph to create
        :type file_name: str

    """
    # strong prefer to have this discoverable, perhaps in the metadata
    test_types = ['instances', 'count', 'group']

    marathon_type = metadata['marathon']
    error_plot = None
    fig = None
    time_plot = None

    # figure and plots setup
    if error_graph_enabled(stats, marathon_type, test_types):
        fig, (time_plot, error_plot) = plt.subplots(nrows=2)
    else:
        fig, time_plot = plt.subplots(nrows=1)

    # figure size, borders and padding
    fig.subplots_adjust(left=0.12, bottom=0.08, right=0.90, top=0.90, wspace=0.25, hspace=0.40)
    fig.set_size_inches(9.5, 9.5)

    # Titles and X&Y setup
    time_plot.title.set_text('Marathon Scale Test for v{}'.format(metadata['marathon-version']))
    targets = get_scale_targets(stats, marathon_type, test_types)
    if targets is None:
        raise GraphException('Unable to create graph due without targets')

    xticks = np.array(range(len(targets)))

    plt.xticks(xticks, targets)
    time_plot.set_xticks(xticks, targets)
    agents, cpus, mem = get_resources(metadata)
    time_plot.set_xlabel('Scale Targets on {} nodes with {} cpus and {} mem'.format(agents, cpus, mem))
    time_plot.set_ylabel('Time to Reach Scale (sec)')
    time_plot.grid(True)

    # graph of all the things
    for test_type in test_types:
        plot_test_timing(time_plot, stats, marathon_type, test_type, xticks)
    time_plot.legend(loc='upper center', bbox_to_anchor=(0.47, -0.15),fancybox=False, shadow=False, ncol=5)

    # graph the errors if they exist
    if error_plot is not None:
        top = 1
        for test_type in test_types:
            largest = plot_test_errors(error_plot, stats, marathon_type, test_type, xticks)
            if largest > top:
                top = largest

        error_plot.legend(loc='upper center', bbox_to_anchor=(0.47, -0.10),fancybox=False, shadow=False, ncol=5)
        error_plot.set_ylim(bottom=0, top=roundup_to_nearest_10(top))

    plt.savefig(file_name)


def roundup_to_nearest_10(x):
    return int(math.ceil(x / 10.0)) * 10


def get_scale_targets(stats, marathon_type, test_types):
    """ Returns the scale targets 1, 10, 100, 1000
        It is possible that some tests are ignored so we may have to
        loop to grab the right list.

        :param stats: This map contains the data to be plotted
        :type stats: map
        :param marathon_type: The type of marathon is part of the map key.  For scale tests it is `root` (vs. mom1)
        :type marathon_type: str
        :param test_types: An array of test types to be graphed, usually {instances, count, group}
        :type test_types: array
    """
    targets = None
    for test_type in test_types:
        targets = stats.get(get_key(marathon_type, test_type, 'target'))
        if targets and len(targets) > 0:
            return targets

    return targets


def error_graph_enabled(stats, marathon_type, test_types):
    """ Returns true if there is any error data to graph

        :param stats: This map contains the data to be plotted
        :type stats: map
        :param marathon_type: The type of marathon is part of the map key.  For scale tests it is `root` (vs. mom1)
        :type marathon_type: str
        :param test_types: An array of test types to be graphed, usually {instances, count, group}
        :type test_types: array
    """
    enabled = False
    for test_type in test_types:
        test_errors_key = get_key(marathon_type, test_type, 'errors')
        if test_errors_key is not None:
            test_errors = stats.get(test_errors_key)
            # if there are test errors... graph them
            if test_errors is not None and len(test_errors) > 0:
                return True

    return False


def get_resources(metadata):
    agents = 0
    cpus = 0
    mem = 0
    try:
        agents = metadata['private-agents']
        cpus = metadata['resources']['cpus']
        mem = metadata['resources']['memory']
    except Exception as e:
        print(e)

    return (agents, cpus, mem)


def load(csvfile):
    """ This is suppose to be short-term.  Teammates have better ideas on how to structure the data.
        I would like to not break the ability to call it directly from the test (instead of shelling out).
        index after table header:
        0 - target - expected scale
        1 - max  -  actual scale
        2 - deploy_time
        3 - human_deploy_time
        4 - launch_status
        5 - deployment_status
        6 - errors
    """
    row_keys = ['target', 'max', 'deploy_time', 'human_deploy_time', 'launch_status', 'deployment_status', 'errors']
    stats = empty_stats()
    current_marathon = None
    current_test_type = None
    index_from_header = 0
    with open(csvfile, 'r') as f:
        reader = csv.reader(f, quoting=csv.QUOTE_NONNUMERIC)
        for i, row in enumerate(reader):
            if 'Marathon:' in row:
                current_marathon = row[1]
                current_test_type = row[2]
                index_from_header = 0
            elif len(row) > 0:
                key = get_key(current_marathon, current_test_type, row_keys[index_from_header])
                stats[key] = row
                index_from_header += 1

    return stats


def load_metadata(file):
    with open(file) as json_data:
        return json.load(json_data)


@click.command()
@click.option('--csvfile', default='scale-test.csv', help='Name of csv file to graph')
@click.option('--metadatafile', default='meta-data.json', help='Name of meta-data file to use for graphing')
@click.option('--graphfile', default='scale.png', help='Name of graph to create')
def main(csvfile, metadatafile, graphfile):
    """
        CLI entry point for graphing scale data.
        Typically, scale tests create a scale-test.csv file which contains the graph points.
        It also produces a meta-data.json which is necessary for the graphing process.
    """
    stats = load(csvfile)
    metadata = load_metadata(metadatafile)
    create_scale_graph(stats, metadata, graphfile)


if __name__ == '__main__':
    main()
