from precisely import Matcher
from precisely.results import unmatched
from tenacity import retry, retry_if_result, retry_if_exception_type, RetryError, stop_after_attempt, wait_fixed


class Eventually(Matcher):

    def __init__(self, matcher, wait_fixed, max_attempts):
        self._matcher = matcher
        self._wait_fixed = wait_fixed
        self._max_attempts = max_attempts
        self._last_result = None

    def match(self, item):
        assert callable(item), "The actual value is not callable."

        @retry(wait=wait_fixed(self._wait_fixed/1000), stop=stop_after_attempt(self._max_attempts),
               retry=(retry_if_result(lambda r: r.is_match is not True) | retry_if_exception_type()))
        def try_match():
            actual = item()
            self._last_result = self._matcher.match(actual)
            return stop_after_attempt

        try:
            return try_match()
        except RetryError as e:
            if self._last_result is not None:
                explanation = "after {} retries {}".format(e.last_attempt.attempt_number, self._last_result.explanation)
            else:
                explanation = "after {} retries {}".format(e.last_attempt.attempt_number, e)
            return unmatched(explanation)

    def describe(self):
        return "eventually {}".format(self._matcher.describe())


def eventually(matcher, wait_fixed=1000, max_attempts=3):
    """Retry match if it failed.

    This matcher will retry the inner match after `wait_fixed` milliseconds but
    give up after `max_attempts` tries.

    The provided value has to be a callable:

    start = time.time()
    assert_that(lambda: time.time() - start, eventually(greater_than(2), max_attempts=5))

    This will assert that the delta between the start and now are eventually greater
    than two.
    """
    return Eventually(matcher, wait_fixed, max_attempts)
