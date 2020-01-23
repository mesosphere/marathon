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
  node('docker') {
    stage("Run Pipeline") {
      try {
        checkout scm
        withCredentials([
            usernamePassword(credentialsId: 'a7ac7f84-64ea-4483-8e66-bb204484e58f', passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USER'),
            string(credentialsId: '3f0dbb48-de33-431f-b91c-2366d2f0e1cf',variable: 'AWS_ACCESS_KEY_ID'),
            string(credentialsId: 'f585ec9a-3c38-4f67-8bdb-79e5d4761937',variable: 'AWS_SECRET_ACCESS_KEY')
        ]) {
          sh 'cd ami && docker build -t mesosphere/marathon-build-env:unstable .' // Move out.
	  sh 'sudo -E docker run -d --rm --privileged -v "$(pwd):/var/build" --name mini mesosphere/marathon-build-env:unstable'
          sh 'sudo -E docker exec -w /var/build mini ci/pipeline jenkins'
	}
      } finally {
        sh 'sudo docker kill mini'
        junit(allowEmptyResults: true, testResults: 'type-generator/target/test-reports/*.xml')
        junit(allowEmptyResults: true, testResults: 'target/test-reports/*.xml')
        junit(allowEmptyResults: true, testResults: 'tests/integration/target/test-reports/*.xml')
        archive includes: "*sandboxes.tar.gz"
        archive includes: "*log.tar.gz"
      }
    }

    stage('Release unstable MoM EE Docker Image') {
      if (env.BRANCH_NAME == 'master') {
        build(
             job: '/marathon-dcos-plugins/release-mom-ee-docker-image/master',
             parameters: [string(name: 'from_image_tag', value: 'unstable'), string(name: 'target_tag', value: 'unstable')],
             propagate: true
        )
      }
    }
  }
}
