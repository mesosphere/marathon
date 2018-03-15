# Code Culture

## Overview

The code culture is a set of defaults ascribed to by the Marathon developer team. Since writing software is an optimization problem, we guide our design decisions with defaults, not rules. Deviations from our defaults require justification.

Our defaults are:

* Code is legible on GitHub
* Immutability over mutability
* No smelly code
* Fail loud and proud
* Let it crash
* Don't panic

## Code is Legible on GitHub

This default speaks to the desire to write code that can be understood without needing an IDE. Marathon is written in Scala, so our defaults are specific as such.

### On Implicits

Implicits are an important feature in Scala and are critical for the implementation of type-classes and DSLs.

Prefer:

* Explicit conversion over implicit
* Explicit passing over implicit


For our case, if implicits don't help improve the design of code, we don't use them. We avoid implicit conversions. We also avoid multi-priority implicits. When type classes help improve the design of code, we use implicits for type classes (IE serialization logic).

We prefer implicits to be used for contextual values such as execution contexts or the actor system. However, we prefer to be conservative, and pass the value explicitly for things like validators, authenticators, and normalizers.

### On Type Inference

If you are writing some function chain that has multiple levels of inferred function parameter types, we prefer to specify the parameter types.

Prefer:

```
groups.map { group: Group =>
  group.apps
    .groupBy(_.container.image)
    .map { case (image: String, apps: Seq[App]) =>
      image -> apps.length
    }
  }
```

Over:

```
groups.map { group =>
  group.apps.groupBy(_.container.image).map { case(k, v) =>
    k -> v.length
  }
}
```

### On Playing Code Golf

Code golf is a game in which you implement some program in as few characters as possible.

We prefer not to play this game when working on Marathon. Instead, we focus on removing noise (boilerplate), while preserving valuable signal pertaining to the problem at hand.

### On Imports

We prefer, almost always, that imports go at the top of the file. Additionally, prefer explicit imports over wildcard imports.

Prefer:

```
import org.company.{Stringifier, Demuxer}
```

Over:

```
import org.company._
```

## Immutability Over Mutability

Functional programming is what follows when you don't mutate state. We prefer it, except in performance critical parts of our code.

We have a strong preference to encapsulate mutable state. References to data crossing the boundary of some module should be immutable.

## No Smelly Code

We pay attention to [code smells](https://en.wikipedia.org/wiki/Code_smell) and strive to write well encapsulated code.

* A method should not receive more data than it needs to do its job.
* Changing some behavior should not result in "shotgun surgery"

## Fail Loud and Proud

It is better to do nothing, than to do the wrong thing.

One of my favorite non-examples of this is found in PHP 5:

```
$ echo '<? print(strftime("%Y-%m-%d",strtotime("lol"))) ?>' | php
1970-01-01
```

Another non-example is found in Marathon's history. At one point in time, the storage layer would swallow the exception and return `None` if it could not deserialize some entity successfully, and this led to data loss.

We are strict in what we accept, and in what we emit. If input is not what we expect, don't be fancy.

Fail loudly. Fail proudly.

## Let it Crash

We prefer to focus on state recovery, and not graceful tear down. We'd prefer to just crash and restart, rather than implement many complex error recovery handlers.

## Don't Panic

We prefer to that Exceptions are thrown only when something goes unexpectedly wrong outside of domain of the library.

For example:

* A validation function should return validation errors, not throw them.
* A storage module will throw an exception if a connection is unavailable.
