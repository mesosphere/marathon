---
title: Contributor Guidelines
---


# Contributor Guidelines

## Getting Started

Maybe you already have a bugfix or enhancement in mind.  If not, there are a
number of relatively approachable issues with the label
["good first bug"](https://github.com/mesosphere/marathon/issues?q=is%3Aopen+is%3Aissue+label%3A%22good+first+bug%22).

<!--
## License Agreement

_TODO_: Do we need a CLA?
-->

## Submitting Changes to Marathon

- A GitHub pull request is the preferred way of submitting patch sets. Please
  rebase your pull requests on top of the current master using
  ```git rebase master``, and squash your changes to a single commit as
  described [here](http://gitready.com/advanced/2009/02/10/squashing-commits-with-rebase.html).
  Yes, we want you to rewrite history – the branch on which you are
  implementing your changes is only meant for this pull request. You can
  either rebase before or after you squash your commits, depending on how
  you'd like to resolve potential merge conflicts. The idea behind is that we
  don't want an arbitrary number of commits for one pull request, but exactly
  one commit. This commit should be easy to merge onto master, therefore we
  ask you to rebase to master.

- Any changes in the public API or behavior must be reflected in the project
  documentation.

- Any changes in the public API or behavior must be reflected in the changelog.md.

- Pull requests should include appropriate additions to the unit test suite.

- If the change is a bugfix, then the added tests must fail without the patch
  as a safeguard against future regressions.

- Run all tests via the supplied `./bin/run-tests.sh` script (requires docker).

## Source Files

- Public classes should be defined in a file of the same name, except for
  trivial subtypes of a _sealed trait_.  In that case, the file name must be
  the plural form of the trait, and it must begin with a lowercase letter.

## Style

### Style Checker

Executing the `test` task in SBT also invokes the style checker
([scalastyle](http://www.scalastyle.org/)).  Some basic style issues will
cause the build to fail:

- Public methods that lack an explicit return type annotation.
- Source code lines that exceed 120 columns.

Other potential problems are output as warnings.

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

