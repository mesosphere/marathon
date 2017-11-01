---
title: Contributor Guidelines
---


# Contributor Guidelines

## Contributing Documentation Changes

You were confused about our documentation? You ran into a
pitfall that others also might run into? Help us making the Marathon documentation great.
 
The documentation that is published [here](https://mesosphere.github.io/marathon/) actually gets
generated from what is found in the docs directory.

If you simply want to correct a spelling mistake or improve the wording of a sentence, you can browse
the mark down files [here](https://github.com/mesosphere/marathon/tree/master/docs) and use the edit
button above the markup. That will make it easy to create a pull request that will be reviewed by us.

If want to contribute a larger improvement to our documentation: 

* Edit the files in the docs directory.
* Check the rendered result as described in the 
  [docs/README.md](https://github.com/mesosphere/marathon/blob/master/docs/README.md).
* If you are feeling perfectionistic, check if there is already an issue about this documentation deficiency
  [here](https://github.com/mesosphere/marathon/issues?q=is%3Aopen+is%3Aissue+label%3Adocs).
  Prefix your commit message with with "Fixes #1234 - " where #1234 is the number of the issue.
* Create a pull request against master.

Please rebase your pull requests on top of the current master using
  `git fetch origin && git rebase origin/master`, and squash your changes to a single commit as
  described [here](http://gitready.com/advanced/2009/02/10/squashing-commits-with-rebase.html).
  Yes, we want you to rewrite history - the branch on which you are
  implementing your changes is only meant for this pull request. You can
  either rebase before or after you squash your commits, depending on how
  you'd like to resolve potential merge conflicts. The idea behind is that we
  don't want an arbitrary number of commits for one pull request, but exactly
  one commit. This commit should be easy to merge onto master, therefore we
  ask you to rebase to master.
    
After you pull request has been accepted, there is still some manual work that we need to do to publish that 
documentation. But don't worry, we will do that for you.

## Getting Started with Code Changes

Maybe you already have a bugfix or enhancement in mind.  If not, there are a
number of relatively approachable issues with the label
["good first issue"](https://github.com/mesosphere/marathon/issues?q=is%3Aopen+is%3Aissue+label%3A%22good+first+issue%22).

<!--
## License Agreement

_TODO_: Do we need a CLA?
-->

## JIRA Issues (Suggesting Bugs and Improvements)

As a general rule, before any change is made to Marathon, a JIRA ticket is filed for bugs, or for proposed
improvements. As a guideline, an improvement ticket should include the following:

- Include a background and motivation
- An overview of the proposed change
- Some acceptance criteria (how would you manually verify that this change worked?)

As the Marathon product team, we don't make changes to the code without discussing the impact and viability of the
proposed changes with each other, first. Creating a JIRA before submitting a code change sets the stage for this
necessary discussion to happen, consensus on direction to be achieved, ideally before too much effort is spent coding
it.

If a change isn't discussed before the code is submitted, there is a higher chance there will be some unconsidered
side-effect of the change, or that the change is not in alignment with the long-term vision of Marathon.

Sometimes, we are overwhelmed by the never ending sea of JIRA. If you submit an issue, and don't feel it is getting the
level of attention that it should, please find us in the
[#marathon channel](https://mesos.slack.com/messages/C1L7D22KY/) on the Mesos community Slack.

## Backporting Policy

All development (new features and bug fixes) are made, first, to the master branch.

If code is heavily refactored but the behavior has not been changed, these patches are generally not backported unless
it will help reduce merge conflicts in the future.

Bugs are backported to the current supported versions of Marathon. Historically, this usually tends to be the
current stable release, plus the past 2 major releases. (IE, if `1.5.6` is the last stable release, then `1.4.x` and `1.3.x` would also
receive the fix).

New features are rarely backported; when they are, they are backported to the latest stable version of Marathon. Major
new features are definitely not backported.

## Submitting Code Changes to Marathon

1. A GitHub pull request is the preferred way of submitting code changes.

2. If a JIRA issue doesn't already exist, please open one for the proposed change in behavior (see the section above,
   "JIRA Issues").

3. Open up a discussion about the JIRA ticket (ideally, in the comments section, but, also in the
   [#marathon channel](https://mesos.slack.com/messages/C1L7D22KY/). Seek to get buy-off and consensus on the direction
   before making the change.

4. Please rebase your pull requests on top of the current master, as needed, to integrate upstream changes and resolve
   conflicts. You can squash your commits if it helps resolve potential merge conflicts. When your PR is merged, it will
   be squashed to a single commit.

5. If you are targeting to get something fixed for an older release of Marathon, fix it on master, first. If it is
   already fixed in master, first, find out why. See our "Backporting Policy" above.

6. Please include in your commit message the line "JIRA Issues: MARATHON-1234", where MARATHON-1234 is the JIRA issue
   mentioned in item 2 above. This helps reviewers better understand the context of the change.

7. If you change modifies the public API or behavior, then the project documentation must be updated (in the same pull
   request). Further, notes about the change should be specified in changelog.md.

8. Pull requests should include appropriate additions to the unit test suite (see "Test Guidelines", below). If the
   change is a bugfix, then the added tests must fail without the fix as a safeguard against future regressions.

9. Compile your code prior to committing. We would like the result of our automatic code formatters to be included in
   the commit as to not produce a dirty work tree after fresh checkout and first compile.

10. Run, at the very least, all unit tests (`sbt test`). Integration tests can also be run using the supplied
    `./bin/run-tests.sh` script (requires docker).

## Test Guidelines

### General guidelines

- Tests should extend `mesosphere.UnitTest`
- Tests should avoid testing more behavior than necessary; IE - don't test other libs or services if a mock/stub
  suffices.
- Tests must be deterministic. Whenever possible, the system clock should not be an input to the test.

### Fixtures

Our testing guidelines regarding fixtures are as follows:

- DO NOT use `var thing: Type = _` with `before` / `after`.
- Fixture instantiation and tear down should be defined and handled in the same module.
- When testing actor systems, we instantiate an actor system for the entire suite. It's acceptable to let the actors get
  cleaned up when the suite finishes. Use either unique actor names or use the loan-fixtures technique to get a unique
  `ActorRefFactory` per test.
- When teardown-per-test is desired, use the
  [loan-fixtures methods](http://www.scalatest.org/user_guide/sharing_fixtures#loanFixtureMethods). Otherwise, prefer
  parameterized case classes or traits.

## Source Files

- Public classes should be defined in a file of the same name, except for
  trivial subtypes of a _sealed trait_.  In that case, the file name must be
  the plural form of the trait, and it must begin with a lowercase letter.

- Some of the source files have diagrams as part of the comments using the [PlantUML](http://plantuml.com) language.  
  To see a rendered diagram use the [online version](http://plantuml.com) or install locally.

## Style

### Style Checker

Executing the ``test`` task in SBT also invokes the style checker.
Some basic style issues will cause the build to fail: While you should fix all of these, you can disable them
if it is a false positive with `// linter:ignore Arg` as listed here: [Linter](https://github.com/HairyFotr/linter).

### Type Annotations

Scala has a powerful type inference engine. The reason for including more type
annotations than are required to make the program compile is to increase
readability.

- Methods with `public` or `protected` scope must have an explicit return type
  annotation.  Without it, an implementaiton change later on could
  accidentally change the public API if a new type is inferred.

- Nontrivial expressions must be explicitly annotated.  Of course, "nontrivial"
  is subjective.  As a rule of thumb, if an expression contains more than one
  higher-order function, annotate the result type and/or consider breaking the
  expression down into simpler intermediate values.

### Null

Assigning the `null` value should be avoided.  It's usually only necessary when
calling into a Java library that ascribes special semantics to `null`.  Prefer
`scala.Option` in all other cases.

### Higher Order Functions

#### GOOD:

```scala
xs.map(f)
```

```scala
xs.map(_.size)
```

```scala
xs.map(_ * 2)
```

```scala
xs.map { item =>
  item -> f(item)
}
```

```scala
xs.map {
  case a: A if a.isEmpty => f(a)
  case a: A              => g(a)
  case b: B              => h(b)
  case other: _          => e(other)
}
```

_Note: match expressions must be exhaustive unless the higher-order function
signature explicitly expects a `PartialFunction[_, _]` as in `collect`, and
`collectFirst`._

```scala
for {
  x <- xs
  y <- x
  z <- y
} yield z
```

#### BAD:

```scala
xs map f // dangerous if more infix operators follow
```

```scala
xs.map(item => f(item)) // use curlies
```

```scala
xs.flatMap(_.flatMap(_.map(_.z))) // replace with a for comprehension
```

