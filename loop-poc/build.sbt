val monocleVersion = "1.4.0" // 1.5.0-cats-M1 based on cats 1.0.0-MF

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-stream" % "2.5.7",

  "com.github.julien-truffaut" %%  "monocle-core"  % monocleVersion,
  "com.github.julien-truffaut" %%  "monocle-macro" % monocleVersion,
  "com.github.julien-truffaut" %%  "monocle-law"   % monocleVersion % "test"
)

scalaVersion := "2.12.4"
