lazy val root = (project in file("."))
  .settings(
    sbtPlugin := true,
    name := "type-generator",
    libraryDependencies ++= Seq(
      "org.raml" % "raml-parser-2" % "1.0.3",
      "com.eed3si9n" %% "treehugger" % "0.4.3",
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.7.2",
      "org.scalatest" %% "scalatest" % "3.0.4" % "test",
      "io.github.java-diff-utils" % "java-diff-utils" % "4.0" % "test",
      "nl.big-o" % "liqp" % "0.7.8"
    ),
    testListeners := Nil, // TODO(MARATHON-8215): Remove this line
    testOptions in Test := Seq(
      Tests.Argument(
        "-u",
        "target/test-reports", // TODO(MARATHON-8215): Remove this line
        "-o",
        "-eDFG",
        "-y",
        "org.scalatest.WordSpec"
      )
    ),
    scalacOptions in Compile ++= Seq(
      "-encoding",
      "UTF-8",
      "-target:jvm-1.8"
    )
  )
