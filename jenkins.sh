#!/bin/bash
set -v
mkdir -p $HOME/.sbt/launchers/0.13.8/
test -r $HOME/.sbt/launchers/0.13.8/sbt-launch.jar || curl -L -o $HOME/.sbt/launchers/0.13.8/sbt-launch.jar http://dl.bintray.com/typesafe/ivy-releases/org.scala-sbt/sbt-launch/0.13.8/sbt-launch.jar
java -jar $HOME/.sbt/launchers/0.13.8/sbt-launch.jar -Dsbt.log.noformat=true "; clean; coverage; doc; assembly"
java -jar $HOME/.sbt/launchers/0.13.8/sbt-launch.jar -Dfile.encoding=utf8  -Dsbt.log.noformat=true "; coverageReport; coveralls"