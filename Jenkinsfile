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
  node('JenkinsMarathonCI-Debian9-2020-01-14') {
    stage("Run Pipeline") {
      try {
        checkout scm
        withCredentials([
            usernamePassword(credentialsId: 'a7ac7f84-64ea-4483-8e66-bb204484e58f', passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USER'),
            string(credentialsId: '3f0dbb48-de33-431f-b91c-2366d2f0e1cf',variable: 'AWS_ACCESS_KEY_ID'),
            string(credentialsId: 'f585ec9a-3c38-4f67-8bdb-79e5d4761937',variable: 'AWS_SECRET_ACCESS_KEY'),
            file(credentialsId: 'DOT_M2_SETTINGS', variable: 'DOT_M2_SETTINGS')
        ]) {
	    withDockerRegistry([credentialsId: 'docker-hub-credentials']) {
	        sh """sudo mkdir -p ~root/.m2 && sudo ln -fs ${DOT_M2_SETTINGS} ~root/.m2/settings.xml"""
	        sh """sudo mkdir -p ~root/.sbt && sudo ln -fs ci/repositories ~root/.sbt/repositories"""
                sh """sudo update-alternatives --set java /usr/lib/jvm/java-8-openjdk-amd64/jre/bin/java"""
                sh """sudo -E ci/pipeline jenkins"""
            }
	}
      } finally {
        junit(allowEmptyResults: true, testResults: 'type-generator/target/test-reports/*.xml')
        junit(allowEmptyResults: true, testResults: 'target/test-reports/*.xml')
        junit(allowEmptyResults: true, testResults: 'tests/integration/target/test-reports/*.xml')
        archive includes: "*sandboxes.tar.gz"
        archive includes: "*log.tar.gz"
      }
    }

    stage('Release MoM EE Docker Image') {
      if (env.BRANCH_NAME == 'master' || env.BRANCH_NAME ==~ /releases\/.*/) {
        version = sh(script: "./version docker", returnStdout: true).trim()
        build(
             job: '/marathon-dcos-plugins/release-mom-ee-docker-image/master',
             parameters: [string(name: 'from_image_tag', value: version)],
             propagate: true
        )
      }
    }
  }
}
