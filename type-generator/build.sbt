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
    ),
    testListeners := Nil, // TODO(MARATHON-8215): Remove this line
    testOptions in Test := Seq(
      Tests.Argument(
        "-u", "target/test-reports", // TODO(MARATHON-8215): Remove this line
        "-o", "-eDFG",
        "-y", "org.scalatest.WordSpec"))
  )
