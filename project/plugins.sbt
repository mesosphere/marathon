// This file contains a list of SBT plugins.

// === resolvers
resolvers += Classpaths.typesafeReleases
resolvers += Classpaths.sbtPluginReleases
resolvers += Resolver.sonatypeRepo("releases")
resolvers += Resolver.sonatypeRepo("snapshots")
resolvers += "Era7 maven releases" at "http://releases.era7.com.s3.amazonaws.com"

// === dependecy graph, https://github.com/jrudolph/sbt-dependency-graph (e.g. `dependencyGraphMl` command)
addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.7.5")

// === IntelliJ Idea project generator, https://github.com/mpeltonen/sbt-idea (e.g. `gen-idea` command)
addSbtPlugin("com.github.mpeltonen" % "sbt-idea" % "1.7.0-SNAPSHOT")

// === deploy fat JARs, restart processes, https://github.com/sbt/sbt-assembly (e.g. `assembly` command)
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.1")

// === release process, https://github.com/sbt/sbt-release (e.g. `release` command)
addSbtPlugin("com.github.gseitz" % "sbt-release" % "1.0.0")

// === automatic code format before each compilation, https://github.com/sbt/sbt-scalariform (e.g. `scalariformFormat` command)
addSbtPlugin("com.typesafe.sbt" % "sbt-scalariform" % "1.3.0")

// === make compile-time (SBT) information available at run-time, https://github.com/sbt/sbt-buildinfo
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.5.0")

// === super-fast development turnaround, https://github.com/spray/sbt-revolver
addSbtPlugin("io.spray" % "sbt-revolver" % "0.7.2")

// === support for Scalastyle, https://github.com/scalastyle/scalastyle-sbt-plugin, http://www.scalastyle.org/sbt.html (e.g. `scalastyle` command)
addSbtPlugin("org.scalastyle" %% "scalastyle-sbt-plugin" % "0.7.0")

// === helps to resolve dependencies from and publish to Amazon S3 buckets (private or public), https://github.com/ohnosequences/sbt-s3-resolver
addSbtPlugin("ohnosequences" % "sbt-s3-resolver" % "0.10.1")

// === code coverage tool, https://github.com/scoverage/sbt-scoverage
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.0.4")

// === uploads scala code coverage to https://coveralls.io and integrates with Travis CI, https://github.com/scoverage/sbt-coveralls
addSbtPlugin("org.scoverage" % "sbt-coveralls" % "1.0.0")

