#!/usr/bin/env python

import os
import requests
import sys

# Map from jenkins status to Github status
jenkins_github = {
        'PENDING': 'pending',
        'SUCCESS': 'success',
        'null': 'success',
        'UNSTABLE': 'failure',
        'FAILURE': 'failure'}


def main(gh_user, gh_password, commit, status, target_url, context):
    uri = 'https://api.github.com/repos/mesosphere/marathon/statuses/{}'.format(commit)
    data = {
        'state': jenkins_github[status],
        'target_url': target_url,
        'context': context}
    requests.post(uri, json=data, auth=(gh_user, gh_password)).raise_for_status()


if __name__ == "__main__":
    gh_user = os.environ['GIT_USER']
    gh_password = os.environ['GIT_PASSWORD']

    context = sys.argv[1]
    target_url = sys.argv[2]
    commit = sys.argv[3]
    status = sys.argv[4]

    main(gh_user, gh_password, commit, status, target_url, context)
