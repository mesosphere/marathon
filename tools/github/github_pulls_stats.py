#!/usr/bin/env python
import collections
import json
import os
import requests

from datetime import datetime
from tabulate import tabulate

IdleTimeRecord = collections.namedtuple('IdleTimeRecord', ['pull_request', 'idle_time'])

def created_at(pull_request):
    # String format can parse: 2018-01-29T16:23:55Z, 2018-08-17T08:41:36Z
    return datetime.strptime(pull_request['created_at'], '%Y-%m-%dT%H:%M:%SZ')


def open_pull_requests_age():
    uri = 'https://api.github.com/repos/mesosphere/marathon/pulls'
    content = requests.get(uri, params={'state':'open'}).json()
    now = datetime.now()
    ages = [now - created_at(pull_request) for pull_request in content]

    ages = sorted(ages)
    p50_index = int(len(ages) * 0.5)
    p90_index = int(len(ages) * 0.9)

    print("In {:d} pull requests".format(len(ages)))
    print("Min:", ages[0])
    print("P50:", ages[p50_index])
    print("P90:", ages[p90_index])
    print("Max:", ages[-1])


def actions(pull_request):
    number = pull_request['number']
    try:
        comment = pull_request['comments']['nodes'][0]
        last_action = comment.get('lastEditedAt') or comment.get('publishedAt')
        last_action = datetime.strptime(last_action, '%Y-%m-%dT%H:%M:%SZ')
        return (number, last_action)
    except IndexError:
        return (number, None)

def open_pull_requests_last_action():
    gh_user = os.environ['GIT_USER']
    gh_password = os.environ['GIT_PASSWORD']

    uri = 'https://api.github.com/graphql'
    # TODO: The query only queries the last comment and ignores PRs without comments.
    with open('comment_dates.graphql') as f:
        query = f.read()
    response = requests.post(uri, json={'query': query}, auth=(gh_user, gh_password))
    data = response.json()['data']

    data = [actions(pr) for pr in data['repository']['pullRequests']['nodes']]
    now = datetime.now()
    idle_times = [IdleTimeRecord(pull_request=action[0], idle_time=now - action[1])
                  for action in data if action[1] is not None]

    idle_times = sorted(idle_times, key=lambda v: v[1])
    p50_index = int(len(idle_times) * 0.5)
    p90_index = int(len(idle_times) * 0.9)

    headers = ['', 'Time since last comment', 'Pull Request']
    table = [['Min', idle_times[0].idle_time, idle_times[0].pull_request],
             ['P50', idle_times[p50_index].idle_time, idle_times[p50_index].pull_request],
             ['P90', idle_times[p90_index].idle_time, idle_times[p90_index].pull_request],
             ['Max', idle_times[-1].idle_time, idle_times[-1].pull_request]
            ]
    print(tabulate(table, headers=headers, tablefmt='html'))


if __name__ == "__main__":
    #open_pull_requests_age()
    open_pull_requests_last_action()
