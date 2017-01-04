node('JenkinsMarathonCI-Debian8') {
    stage("Checkout Master") {
        git branch: 'master', credentialsId: '4ff09dce-407b-41d3-847a-9e6609dd91b8', url: 'git@github.com:mesosphere/marathon'
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
        sh "sudo -E sbt -Dsbt.log.format=false clean compile || true"
      }
    }
    stage("Run tests") {
      withEnv(['RUN_DOCKER_INTEGRATION_TESTS=true', 'RUN_MESOS_INTEGRATION_TESTS=true']) {
         sh "sudo -E sbt -Dsbt.log.format=false test || true"
         junit allowEmptyResults: true, testResults: 'target/test-reports/**/*.xml'

      }
    }
    stage("Run integration tests") {
      withEnv(['RUN_DOCKER_INTEGRATION_TESTS=true', 'RUN_MESOS_INTEGRATION_TESTS=true']) {
         sh "sudo -E sbt -Dsbt.log.format=false integration:test || true"
         junit allowEmptyResults: true, testResults: 'target/test-reports/integration/**/*.xml'
      }
    }
}
