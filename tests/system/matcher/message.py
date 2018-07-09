import retrying
from precisely import Matcher
from precisely.results import matched, unmatched


class AppFailureMessage(Matcher):

    def __init__(self, error_message):
        self.expected = error_message

    def match(self, actual):
        message = actual['lastTaskFailure']['message']
        # TODO: verify that item is app
        if self.expected in message:
            return matched()
        else:
            return unmatched("has no failure message '{}'".format(self.expected))

    def describe(self):
        return "has failure message '{}'".format(self.expected)


def has_failure_message(error_message):
    return AppFailureMessage(error_message)
