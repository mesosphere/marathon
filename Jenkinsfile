#!/usr/bin/env groovy

ansiColor('xterm') {
  node('JenkinsMarathonCI-Debian8-2017-10-23') {
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
        junit allowEmptyResults: true, testResults: 'target/test-reports/*integration/*.xml'
        publishHTML([
            allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true,
            reportDir: 'target/scala-2.12/scapegoat-report', reportFiles: 'scapegoat.html',
            reportName: 'Scapegoat Report', reportTitles: ''
        ])
        archive includes: "sandboxes.tar.gz"
        archive includes: "ci-${env.BUILD_TAG}.log.tar.gz"
        archive includes: "ci-${env.BUILD_TAG}.log"  // Only in case the build was  aborted and the logs weren't zipped
      }
    }
  }
}
