lazy val root = (project in file("."))
  .settings(
    sbtPlugin := true,
    name := "raml-codegen",
    libraryDependencies ++= Seq(
      "org.raml" % "raml-parser-2" % "1.0.3",
      "com.eed3si9n" %% "treehugger" % "0.4.3"
    )
  )
