#!/bin/bash
#
# sbt launcher script
#
# @see http://www.scala-sbt.org/0.13/tutorial/Manual-Installation.html
#

SBT_OPTS="-Xms512M -Xmx1536M -Xss1M -XX:+CMSClassUnloadingEnabled"
java $SBT_OPTS -jar `dirname $0`/sbt-launch.jar "$@"
