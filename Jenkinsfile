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

def previousBuildFailed() {
    def previousResult = currentBuild.rawBuild.getPreviousBuild()?.getResult()
    return !hudson.model.Result.SUCCESS.equals(previousResult)
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

node('JenkinsMarathonCI-Debian8-1-2017-02-23') { try {
        stage("Checkout Repo") {
            checkout scm
            gitCommit = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
            shortCommit = gitCommit.take(8)
            currentBuild.displayName = "#${env.BUILD_NUMBER}: ${shortCommit}"
        }
        stageWithCommitStatus("1. Compile") {
          try {
            sh """if grep -q MesosDebian \$WORKSPACE/project/Dependencies.scala; then
                    MESOS_VERSION=\$(sed -n 's/^.*MesosDebian = "\\(.*\\)"/\\1/p' <\$WORKSPACE/project/Dependencies.scala)
                  else
                    MESOS_VERSION=\$(sed -n 's/^.*mesos=\\(.*\\)&&.*/\\1/p' <\$WORKSPACE/Dockerfile)
                  fi
                  sudo apt-get install -y --force-yes --no-install-recommends mesos=\$MESOS_VERSION
              """
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
        stage("4. Assemble Runnable Binaries") {
          sh "sudo -E sbt assembly"
          sh "sudo bin/build-distribution"
        }
        stage("5. Package Binaries") {
          parallel (
            "Tar Binaries": {
              sh """sudo tar -czv -f "target/marathon-${gitCommit}.tgz" \
                      Dockerfile \
                      README.md \
                      LICENSE \
                      bin \
                      examples \
                      docs \
                      target/scala-2.*/marathon-assembly-*.jar
                 """
            },
            "Create Debian and Red Hat Package": {
              sh "sudo rm -rf marathon-pkg && git clone https://github.com/mesosphere/marathon-pkg.git marathon-pkg"
              dir("marathon-pkg") {
                 // marathon-pkg has marathon as a git module. We've already
                 // checked it out. So let's just symlink.
                 sh "sudo rm -rf marathon && ln -s ../ marathon"
                 sh "sudo make all"
              }
            },
            "Build Docker Image": {
              // target is in .dockerignore so we just copy the jar before.
              sh "cp target/*/marathon-assembly-*.jar ."
              mesosVersion = sh(returnStdout: true, script: "sed -n 's/^.*MesosDebian = \"\\(.*\\)\"/\\1/p' <./project/Dependencies.scala").trim()
              docker.build("mesosphere/marathon:${gitCommit}", "--build-arg MESOS_VERSION=${mesosVersion} .")
           }
        )
      }
      stage("6. Archive Artifacts") {
          archiveArtifacts artifacts: 'target/**/classes/**', allowEmptyArchive: true
          archiveArtifacts artifacts: 'target/marathon-runnable.jar', allowEmptyArchive: true
          archiveArtifacts artifacts: "target/marathon-${gitCommit}.tgz", allowEmptyArchive: false
          archiveArtifacts artifacts: "marathon-pkg/marathon*.deb", allowEmptyArchive: false
          archiveArtifacts artifacts: "marathon-pkg/marathon*.rpm", allowEmptyArchive: false
          step([
              $class: 'S3BucketPublisher',
              entries: [[
                  sourceFile: "target/marathon-${gitCommit}.tgz",
                  bucket: 'marathon-artifacts',
                  selectedRegion: 'us-west-2',
                  noUploadOnFailure: true,
                  managedArtifacts: true,
                  flatten: true,
                  showDirectlyInBrowser: false,
                  keepForever: true,
              ]],
              profileName: 'marathon-artifacts',
              dontWaitForConcurrentBuildCompletion: false,
              consoleLogLevel: 'INFO',
              pluginFailureResultConstraint: 'FAILURE'
          ])
      }
      // Only create latest-dev snapshot for master.
      if( env.BRANCH_NAME == "master" ) {
        stage("7. Publish Docker Image Snaphot") {
          docker.image("mesosphere/marathon:${gitCommit}").tag("latest-dev")
          docker.withRegistry("https://index.docker.io/v1/", "docker-hub-credentials") {
            docker.image("mesosphere/marathon:latest-dev").push()
          }
        }
      }
    } catch (Exception err) {
        currentBuild.result = 'FAILURE'
        if( env.BRANCH_NAME.startsWith("releases/") || env.BRANCH_NAME == "master" ) {
          slackSend(
            message: "(;¬_¬) branch `${env.BRANCH_NAME}` failed in build `${env.BUILD_NUMBER}`. (<${env.BUILD_URL}|Open>)",
            color: "danger",
            channel: "#marathon-dev",
            tokenCredentialId: "f430eaac-958a-44cb-802a-6a943323a6a8")
        }
        throw err
    } finally {
        if( env.BRANCH_NAME.startsWith("releases/") || env.BRANCH_NAME == "master" ) {
            // Last build failed but this succeeded.
            if( previousBuildFailed() && currentBuild.result == 'SUCCESS') {
              slackSend(
                message: "╭( ･ㅂ･)و ̑̑ branch `${env.BRANCH_NAME}` is green again. (<${env.BUILD_URL}|Open>)",
                color: "good",
                channel: "#marathon-dev",
                tokenCredentialId: "f430eaac-958a-44cb-802a-6a943323a6a8")
            }
        }

        step([ $class: 'GitHubCommitStatusSetter'
             , errorHandlers: [[$class: 'ShallowAnyErrorHandler']]
             , contextSource: [$class: 'ManuallyEnteredCommitContextSource', context: "Velocity All"]
             ])
    }
}
