import common
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
            return unmatched("failure message '{}'".format(self.expected))

    def describe(self):
        return "failure message '{}'".format(self.expected)


class HasEventually(Matcher):

    def __init__(self, matcher):
        self.matcher = matcher

    def match(self, item):
        assert callable(item), "The actual value is not callable."

        @retrying.retry(
                wait_fixed=1000, stop_max_attempt_number=3,
                retry_on_exception=common.ignore_exception,
                retry_on_result=lambda r: r.is_match is not True)
        def try_match():
            actual = item()
            return self.matcher.match(actual)

        try:
            return try_match()
        except retrying.RetryError as e:
            explanation = "after {} retries has no {}".format(e.last_attempt.attempt_number, self.matcher.describe())
            return unmatched(explanation)

    def describe(self):
        return "has eventually {}".format(self.matcher.describe())


def failure_message(error_message):
    return AppFailureMessage(error_message)


def has_eventually(matcher):
    return HasEventually(matcher)
