#!/usr/bin/env python

import requests

from datetime import datetime


def created_at(pull_request):
    # String format can parse: 2018-01-29T16:23:55Z, 2018-08-17T08:41:36Z
    return datetime.strptime(pull_request['created_at'], '%Y-%m-%dT%H:%M:%SZ')


def main():
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


if __name__ == "__main__":
    main()
