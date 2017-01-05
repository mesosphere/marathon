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
          withEnv(['RUN_DOCKER_INTEGRATION_TESTS=true', 'RUN_MESOS_INTEGRATION_TESTS=true']) {
            sh "sudo -E sbt -Dsbt.log.format=false clean compile"
          }
        }
        stage("Run tests") {
          try {
              withEnv(['RUN_DOCKER_INTEGRATION_TESTS=true', 'RUN_MESOS_INTEGRATION_TESTS=true']) {
                 sh "sudo -E sbt -Dsbt.log.format=false test"
              }
          } catch (Exception err) {
             junit allowEmptyResults: true, testResults: 'target/test-reports/**/*.xml'
             throw err
          }
        }
        stage("Run integration tests") {
          try {
              withEnv(['RUN_DOCKER_INTEGRATION_TESTS=true', 'RUN_MESOS_INTEGRATION_TESTS=true']) {
                 sh "sudo -E sbt -Dsbt.log.format=false integration:test"
              }
          } catch (Exception err) {
             junit allowEmptyResults: true, testResults: 'target/test-reports/integration/**/*.xml'
             throw err
          }
        }
    } catch (Exception err) {
        currentBuild.result = 'FAILURE'
    } finally {
        step([ $class: 'GitHubCommitStatusSetter'
             , errorHandlers: [[$class: 'ShallowAnyErrorHandler']]
             , message: "Velocity - " + currentBuild.description
             , statusResultSource: [
                 $class: 'ConditionalStatusResultSource'
               , results: [
                   [$class: 'BetterThanOrEqualBuildResult', result: 'SUCCESS', state: 'SUCCESS', message: "Velocity - " + currentBuild.description]
                 , [$class: 'BetterThanOrEqualBuildResult', result: 'FAILURE', state: 'FAILURE', message: "Velocity - " + currentBuild.description]
                 , [$class: 'AnyBuildResult', state: currentBuild.result, message: "Velocity - " + currentBuild.description]
                 ]
               ]
             ])
    }
}
