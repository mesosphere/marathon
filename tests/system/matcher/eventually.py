import common
import retrying
from precisely import Matcher
from precisely.results import unmatched


class Eventually(Matcher):

    def __init__(self, matcher, wait_fixed, max_attempts):
        self.matcher = matcher
        self._wait_fixed = wait_fixed
        self._max_attempts = max_attempts

    def match(self, item):
        assert callable(item), "The actual value is not callable."

        @retrying.retry(
                wait_fixed=self._wait_fixed,
                stop_max_attempt_number=self._max_attempts,
                retry_on_exception=common.ignore_exception,
                retry_on_result=lambda r: r.is_match is not True)
        def try_match():
            actual = item()
            return self.matcher.match(actual)

        try:
            return try_match()
        except retrying.RetryError as e:
            explanation = "after {} retries {}".format(e.last_attempt.attempt_number, e.last_attempt.value.explanation)
            return unmatched(explanation)

    def describe(self):
        return "eventually {}".format(self.matcher.describe())


def eventually(matcher, wait_fixed=1000, max_attempts=3):
    return Eventually(matcher, wait_fixed, max_attempts)
