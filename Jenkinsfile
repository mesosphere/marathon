#!/usr/bin/env groovy

@Library('sec_ci_libs@v2-latest') _

def master_branches = ["master", ] as String[]

ansiColor('xterm') {
  // using shakedown node because it's a lightweight alpine docker image instead of full VM
  node('shakedown') {
    stage("Verify author") {
      user_is_authorized(master_branches, '8b793652-f26a-422f-a9ba-0d1e47eb9d89', '#marathon-dev')
    }
  }
  node('JenkinsMarathonCI-Debian9-2018-04-09') {
    stage("Run Pipeline") {
      try {
        checkout scm
        withCredentials([
            usernamePassword(credentialsId: 'a7ac7f84-64ea-4483-8e66-bb204484e58f', passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USER'),
            string(credentialsId: '3f0dbb48-de33-431f-b91c-2366d2f0e1cf',variable: 'AWS_ACCESS_KEY_ID'),
            string(credentialsId: 'f585ec9a-3c38-4f67-8bdb-79e5d4761937',variable: 'AWS_SECRET_ACCESS_KEY')
        ]) {
            sh """sudo -E ci/pipeline jenkins"""
        }
      } finally {
        junit(allowEmptyResults: true, testResults: 'target/test-reports/*.xml')
        junit(allowEmptyResults: true, testResults: 'tests/integration/target/test-reports/*.xml')
        archive includes: "*sandboxes.tar.gz"
        archive includes: "*log.tar.gz"
      }
    }
  }
}
