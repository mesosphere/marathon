lazy val root = (project in file("."))
  .settings(
    sbtPlugin := true,
    name := "type-generator",
    libraryDependencies ++= Seq(
      "org.raml" % "raml-parser-2" % "1.0.3",
      "com.eed3si9n" %% "treehugger" % "0.4.3",
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "org.scalatest" %% "scalatest" % "3.0.4" % "test",
      "io.github.java-diff-utils" % "java-diff-utils" % "4.0" % "test"
    )
  )
