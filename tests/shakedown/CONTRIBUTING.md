# Contributing to Shakedown
Third-party patches are essential for keeping Shakedown great. We want to keep
it as easy as possible to contribute changes that allow Shakedown to work for
your needs and in your environment. In order to have a chance of keeping up with
community contributions, and to ensure a consistent experience for everyone
involved, we have a few guidelines that we need contributors to follow.

## Submitting Changes to Shakedown
  * A [GitHub pull request][github-pr] is the preferred way of submitting
  patches.
  * Any changes in the public API or behavior must be reflected in the project
  documentation.
  * Pull requests should include appropriate additions to the test suite (if
  applicable).
  * If the change is a bugfix then the added tests must fail without the patch
  applied, as a safeguard against future regressions.

## Style Guidelines
Before submitting your pull request, please consider:
  * Checking for unnecessary whitespace: this can be accomplished by running
  the command `git diff --check`. All tabs should be expanded to spaces; hard
  tabs are not allowed.
  * Please keep line length to around 79-80 characters. We understand that this
  isn't always practical; just use your best judgement.
  * Include comments and documentation where appropriate.
  * Any changes should follow the guidelines set forth in [PEP-8][pep-8].
  * Please, no additional copyright statements or licenses in source files.

Additionally, we would prefer the following format for commit messages:

```
A brief summary, no more than 50 characters

Prior to this patch, there wasn't an example of a commit message in the
contributing documentation. This had the side effect of the user having
no idea what an acceptable commit message might look like.

This commit fixes the problem by introducing a real-world example to
the contributing documentation.
```

[github-pr]: https://help.github.com/articles/using-pull-requests
[pep-8]: https://www.python.org/dev/peps/pep-0008/
