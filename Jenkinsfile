#!/usr/bin/env groovy

ansiColor('gnome-terminal') {
  node('JenkinsMarathonCI-Debian8-2017-04-27') {
    stage("Run Pipeline") {
      checkout scm
      sh """sudo -E ci/pipeline jenkins"""
    }
  }
}
