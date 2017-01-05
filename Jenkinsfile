def withCommitStatus(context, block) {
  try {
    block()
    currentBuild.result = 'SUCCESS'
  } catch(error) {
    currentBuild.result = 'FAILURE'
    throw error
  } finally {
    step([ $class: 'GitHubCommitStatusSetter'
         , contextSource: [$class: 'ManuallyEnteredCommitContextSource', context: context]
         ])
  }
}

node('JenkinsMarathonCI-Debian8') {
    try {
        stage("Checkout Repo") {
            checkout scm
            gitCommit = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
            shortCommit = gitCommit.take(8)
            currentBuild.displayName = "#${env.BUILD_NUMBER}: ${shortCommit}"
        }
        stage("Install Mesos") {
            sh "sudo apt-get -y update"
            sh "sudo apt-get install -y --force-yes --no-install-recommends curl"
            sh """if grep -q MesosDebian \$WORKSPACE/project/Dependencies.scala; then
        MESOS_VERSION=\$(sed -n 's/^.*MesosDebian = "\\(.*\\)"/\\1/p' <\$WORKSPACE/project/Dependencies.scala)
        sudo apt-get install -y --force-yes --no-install-recommends mesos=\$MESOS_VERSION
      else
        MESOS_VERSION=\$(sed -n 's/^.*mesos=\\(.*\\)&&.*/\\1/p' <\$WORKSPACE/Dockerfile)
        sudo apt-get install -y --force-yes --no-install-recommends mesos=\$MESOS_VERSION
      fi"""
        }
        stage("Compile") {
          withCommitStatus("Velocity Compile") {
            withEnv(['RUN_DOCKER_INTEGRATION_TESTS=true', 'RUN_MESOS_INTEGRATION_TESTS=true']) {
              sh "exit 0"
              // sh "sudo -E sbt -Dsbt.log.format=false clean compile scapegoat"
            }
          }
        }
        stage("Run tests") {
          try {
              currentBuild.result = 'PENDING'
              withEnv(['RUN_DOCKER_INTEGRATION_TESTS=true', 'RUN_MESOS_INTEGRATION_TESTS=true']) {
                 sh "exit 1"
                 sh "sudo -E sbt -Dsbt.log.format=false test"
              }
          } catch (Exception err) {
             currentBuild.result = 'FAILURE'
             junit allowEmptyResults: true, testResults: 'target/test-reports/**/*.xml'
             throw err
          } finally {
            currentBuild.result = 'SUCCESS'
            step([ $class: 'GitHubCommitStatusSetter'
                 , errorHandlers: [[$class: 'ShallowAnyErrorHandler']]
                 , contextSource: [$class: 'ManuallyEnteredCommitContextSource', context: "Velocity Tests"]
                 ])
          }
        }
        stage("Run integration tests") {
          try {
            currentBuild.result = 'PENDING'
            withEnv(['RUN_DOCKER_INTEGRATION_TESTS=true', 'RUN_MESOS_INTEGRATION_TESTS=true']) {
               sh "sudo -E sbt -Dsbt.log.format=false integration:test"
            }
          } catch (Exception err) {
            currentBuild.result = 'FAILURE'
            junit allowEmptyResults: true, testResults: 'target/test-reports/integration/**/*.xml'
            throw err
          } finally {
            currentBuild.result = 'SUCCESS'
            step([ $class: 'GitHubCommitStatusSetter'
                 , errorHandlers: [[$class: 'ShallowAnyErrorHandler']]
                 , contextSource: [$class: 'ManuallyEnteredCommitContextSource', context: "Velocity Integration Tests"]
                 ])
          }
        }
        stage("Create docs") {
            sh "sudo -E sbt -Dsbt.log.format=false doc"
        }
    } catch (Exception err) {
        currentBuild.result = 'FAILURE'
    } finally {
        step([ $class: 'GitHubCommitStatusSetter'
             , errorHandlers: [[$class: 'ShallowAnyErrorHandler']]
             ])
    }
}
