#!/usr/bin/env groovy
// so we can use marathon.groovy
def m


node('JenkinsMarathonCI-Debian8-2017-03-21') {
  try {
    stage("Checkout Repo") {
      checkout scm
      gitCommit = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
      shortCommit = gitCommit.take(8)
      currentBuild.displayName = "#${env.BUILD_NUMBER}: ${shortCommit}"
      sh """git tag | grep phabricator | xargs git tag -d || true """
    }
    m = load("marathon.groovy")
    m.install_dependencies()

    stage("Kill junk processes") {
      m.kill_junk()
    }
    stage("Install Mesos") {
      m.install_mesos()
    }
    m.stageWithCommitStatus("1. Compile") {
      try {
        m.compile()
      } finally {
        archiveArtifacts artifacts: 'target/**/scapegoat-report/scapegoat.html', allowEmptyArchive: true
      }
    }
    m.stageWithCommitStatus("2. Test") {
      try {
        m.test()
      } finally {
        sh "sudo mv target/scala-2.11/scoverage-report/ target/scala-2.11/scoverage-report-unit"
        sh "sudo mv target/scala-2.11/coverage-report/cobertura.xml target/scala-2.11/coverage-report/cobertura-unit.xml"
        archiveArtifacts(
            artifacts: 'target/**/coverage-report/cobertura-unit.xml, target/**/scoverage-report-unit/**',
            allowEmptyArchive: true)
      }
    }
    m.stageWithCommitStatus("3. Test Integration") {
      try {
        m.integration_test()
      } finally {
        // scoverage does not allow the configuration of a different output
        // path: https://github.com/scoverage/sbt-scoverage/issues/211
        // The archive steps does not allow a different target path. So we
        // move the files to avoid conflicts with the reports from the unit
        // test run.
        sh "sudo mv target/scala-2.11/scoverage-report/ target/scala-2.11/scoverage-report-integration"
        sh "sudo mv target/scala-2.11/coverage-report/cobertura.xml target/scala-2.11/coverage-report/cobertura-integration.xml"
        archiveArtifacts(
            artifacts: 'target/**/coverage-report/cobertura-integration.xml, target/**/scoverage-report-integration/**',
            allowEmptyArchive: true)
      }
    }
    stage("4. Assemble Runnable Binaries") {
      m.assembly()
    }
    stage("5. Package Binaries") {
      m.package_binaries()
    }
    stage("6. Run Unstable Tests") {
      if (m.has_unstable_tests()) {
        try {
          m.unstable_test()
        } catch (err) {
          // For PRs, can we report it there somehow?
          if (env.BRANCH_NAME.startsWith("releases/") || env.BRANCH_NAME == "master") {
            slackSend(message: "\u26a0 branch `${env.BRANCH_NAME}` failed in build `${env.BUILD_NUMBER}`. (<${env.BUILD_URL}|Open>)",
                color: "danger",
                channel: "#marathon-dev",
                tokenCredentialId: "f430eaac-958a-44cb-802a-6a943323a6a8")
          }
        }
      }
    }
    stage("7. Archive Artifacts") {
      archiveArtifacts artifacts: 'target/**/classes/**', allowEmptyArchive: true
      archiveArtifacts artifacts: 'target/marathon-runnable.jar', allowEmptyArchive: true
      archiveArtifacts artifacts: "target/marathon-${gitVersion}.tgz", allowEmptyArchive: false
      archiveArtifacts artifacts: "packaging/marathon*.deb", allowEmptyArchive: false
      archiveArtifacts artifacts: "packaging/marathon*.rpm", allowEmptyArchive: false
      step([
          $class: 'S3BucketPublisher',
          entries: [[
              sourceFile: "target/marathon-*.tgz",
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
    if (env.BRANCH_NAME == "master") {
      stage("8. Publish Docker Image Snaphot") {
        docker.image("mesosphere/marathon:${gitVersion}").tag("latest-dev")
        docker.withRegistry("https://index.docker.io/v1/", "docker-hub-credentials") {
          docker.image("mesosphere/marathon:latest-dev").push()
        }
      }
    }
  } catch (Exception err) {
    currentBuild.result = 'FAILURE'
    if (env.BRANCH_NAME.startsWith("releases/") || env.BRANCH_NAME == "master") {
      slackSend(
          message: "(;¬_¬) branch `${env.BRANCH_NAME}` failed in build `${env.BUILD_NUMBER}`. (<${env.BUILD_URL}|Open>)",
          color: "danger",
          channel: "#marathon-dev",
          tokenCredentialId: "f430eaac-958a-44cb-802a-6a943323a6a8")
    }
    throw err
  } finally {
    if (env.BRANCH_NAME.startsWith("releases/") || env.BRANCH_NAME == "master") {
      // Last build failed but this succeeded.
      if (m.previousBuildFailed() && currentBuild.result == 'SUCCESS') {
        slackSend(
            message: "╭( ･ㅂ･)و ̑̑ branch `${env.BRANCH_NAME}` is green again. (<${env.BUILD_URL}|Open>)",
            color: "good",
            channel: "#marathon-dev",
            tokenCredentialId: "f430eaac-958a-44cb-802a-6a943323a6a8")
      }
    }

    step([$class: 'GitHubCommitStatusSetter'
        , errorHandlers: [[$class: 'ShallowAnyErrorHandler']]
        , contextSource: [$class: 'ManuallyEnteredCommitContextSource', context: "Velocity All"]
    ])
  }
}
