#!/usr/bin/env groovy

ansiColor('gnome-terminal') {
  node('JenkinsMarathonCI-Debian8-2017-04-27') {
    stage("Run Pipeline") {
      try {
        checkout scm
        sh """sudo -E ci/pipeline jenkins"""
      } finally {
        junit(allowEmptyResults: true, testResults: 'target/test-reports/*.xml')
        junit allowEmptyResults: true, testResults: 'target/test-reports/*integration/*.xml'
      }
    }
  }
}
