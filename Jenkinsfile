#!/usr/bin/env groovy

/**
 * Execute block and set GitHub commit status to success or failure if block
 * throws an exception.
 *
 * @param label The context for the commit status.
 * @param block The block to execute.
 */
def withCommitStatus(label, block) {
  try {
    // Execute steps in stage
    block()

    currentBuild.result = 'SUCCESS'
  } catch(error) {
    currentBuild.result = 'FAILURE'
    throw error
  } finally {

    // Mark commit with final status
    step([ $class: 'GitHubCommitStatusSetter'
         , contextSource: [$class: 'ManuallyEnteredCommitContextSource', context: "Velocity " + label]
         ])
  }
}

/**
 * Wrap block with a stage and a GitHub commit status setter.
 *
 * @param label The label for the stage and commit status context.
 * @param block The block to execute in stage.
 */
def stageWithCommitStatus(label, block) {
  stage(label) { withCommitStatus(label, block) }
}

node('JenkinsMarathonCI-Debian8') {
    try {
        stage("Checkout Repo") {
            checkout scm
            gitCommit = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
            shortCommit = gitCommit.take(8)
            currentBuild.displayName = "#${env.BUILD_NUMBER}: ${shortCommit}"
        }
        stage("Provision Jenkins Node") {
            sh "sudo apt-get -y clean"
            sh "sudo apt-get install -y --force-yes --no-install-recommends curl"
            sh """if grep -q MesosDebian \$WORKSPACE/project/Dependencies.scala; then
        MESOS_VERSION=\$(sed -n 's/^.*MesosDebian = "\\(.*\\)"/\\1/p' <\$WORKSPACE/project/Dependencies.scala)
      else
        MESOS_VERSION=\$(sed -n 's/^.*mesos=\\(.*\\)&&.*/\\1/p' <\$WORKSPACE/Dockerfile)
      fi
      sudo apt-get install -y --force-yes --no-install-recommends mesos=\$MESOS_VERSION
      """
        }
        stageWithCommitStatus("1. Compile") {
          try {
            withEnv(['RUN_DOCKER_INTEGRATION_TESTS=true', 'RUN_MESOS_INTEGRATION_TESTS=true']) {
              sh "sudo -E sbt -Dsbt.log.format=false clean compile scapegoat doc"
            }
          } finally {
            archiveArtifacts artifacts: 'target/**/scapegoat-report/scapegoat.html', allowEmptyArchive: true
          }
        }
        stageWithCommitStatus("2. Test") {
          try {
              timeout(time: 20, unit: 'MINUTES') {
                withEnv(['RUN_DOCKER_INTEGRATION_TESTS=true', 'RUN_MESOS_INTEGRATION_TESTS=true']) {
                   sh "sudo -E sbt -Dsbt.log.format=false coverage test coverageReport"
                }
              }
          } finally {
            junit allowEmptyResults: true, testResults: 'target/test-reports/**/*.xml'
            archiveArtifacts artifacts: 'target/**/coverage-report/cobertura.xml, target/**/scoverage-report/**', allowEmptyArchive: true
          }
        }
        stageWithCommitStatus("3. Test Integration") {
          try {
              timeout(time: 20, unit: 'MINUTES') {
                withEnv(['RUN_DOCKER_INTEGRATION_TESTS=true', 'RUN_MESOS_INTEGRATION_TESTS=true']) {
                   sh "sudo -E sbt -Dsbt.log.format=false coverage integration:test mesos-simulation/integration:test coverageReport"
                }
            }
          } finally {
            junit allowEmptyResults: true, testResults: 'target/test-reports/integration/**/*.xml'
          }
        }
        stage("4. Assemble and Archive Binaries") {
            sh "sudo -E sbt assembly"
            archiveArtifacts artifacts: 'target/**/classes/**', allowEmptyArchive: true
        }
    } catch (Exception err) {
        currentBuild.result = 'FAILURE'
    } finally {
        step([ $class: 'GitHubCommitStatusSetter'
             , errorHandlers: [[$class: 'ShallowAnyErrorHandler']]
             , contextSource: [$class: 'ManuallyEnteredCommitContextSource', context: "Velocity All"]
             ])
    }
}
