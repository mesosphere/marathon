// Libraries of methods for testing Marathon in jenkins

def ignore_error(block) {
  try {
    block()
  } catch(err) {

  }
  return this
}

// Add a prefix to all of the JUnit result files listed
// This is particularly useful for tagging things like "UNSTABLE.${TestName}"
def mark_unstable_results(dirs) {
  // add prefix to qualified classname
  sh """sudo /usr/local/bin/amm scripts/mark_unstable_results.sc $dirs"""
  return this
}

// Run the given phabricator method (e.g. arc call-conduit <method>) with
// the given jq arguments wrapped in a json object.
// e.g. phabricator("differential.revision.edit", """ transactions: [{type: "comment", "value": "Some comment"}], objectIdentifier: "D1" """)
def phabricator(method, args) {
  sh "jq -n '{ $args }' | arc call-conduit $method"
  return this
}

// Report all the test results for the given PHID with the given status to Harbormaster.
// PHID is expected to be set as an environment variable
def phabricator_test_results(status) {
  sh """jq -s add target/phabricator-test-reports/*.json | jq '{buildTargetPHID: "$PHID", type: "$status", unit: . }' | arc call-conduit harbormaster.sendmessage """
  return this
}

// Convert the test coverage into a "fake" unit test result so that
// phabricator_test_results can consume it and report the coverage.
def phabricator_convert_test_coverage() {
  sh """sudo /usr/local/bin/amm scripts/convert_test_coverage.sc """
  return this
}

// Publish the test coverage information into the build.
// When we finally have the publish_html method, this will hopefully work.
def publish_test_coverage(name) {
  //publishHtml([allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'target/scoverage-report', reportFiles: 'index.html', reportName: "Test Coverage"])
  return this
}

// Applies the phabricator diff and posts messages to phabricator
// that the build is in progress, the revision is rejected and
// the harbormaster build has the given URL.
// Ephid: the harbormaster phid to update.
// build_url: The build URL of the jenkins build
// revision_id: the revision id being built, e.g. D123
// diff_id: The diff id to apply (e.g. 2458)
def phabricator_apply_diff(phid, build_url, revision_id, diff_id) {
  phabricator("harbormaster.createartifact", """buildTargetPHID: "$phid", artifactType: "uri", artifactKey: "$build_url", artifactData: { uri: "$build_url", name: "Velocity Results", "ui.external": true }""")
  ignore_error {
    phabricator("differential.revision.edit", """transactions: [{type: "reject", value: true}], objectIdentifier: "D$revision_id" """)
  }
  phabricator("harbormaster.sendmessage", """ buildTargetPHID: "$phid", type: "work" """)
  sh "arc patch --diff $diff_id"
}

// installs mesos at the revision listed in the build.
def install_mesos() {
  def aptInstall = "sudo apt-get install -y --force-yes --no-install-recommends mesos=\$MESOS_VERSION"
  sh """if grep -q MesosDebian \$WORKSPACE/project/Dependencies.scala; then
          MESOS_VERSION=\$(sed -n 's/^.*MesosDebian = "\\(.*\\)"/\\1/p' <\$WORKSPACE/project/Dependencies.scala)
        else
          MESOS_VERSION=\$(sed -n 's/^.*mesos=\\(.*\\)&&.*/\\1/p' <\$WORKSPACE/Dockerfile)
        fi
        ${aptInstall} || sudo apt-get update && ${aptInstall}
      """
  return this
}

// Kill stale processes left-over from old builds.
def kill_junk() {
  sh "/usr/local/bin/amm scripts/kill_stale_test_processes.sc"
}

// Install job-level dependencies that aren't specific to the build and
// can be required as part of checkout and should be applied before knowing
// the revision's information. e.g. JQ is required to post to phabricator.
// This should generally be fixed in the AMI, eventually.
// MARATHON-7026
def install_dependencies() {
  sh "chmod 0600 ~/.arcrc"
  // JQ is broken in the image
  sh "curl -L https://github.com/stedolan/jq/releases/download/jq-1.5/jq-linux64 > /tmp/jq && sudo mv /tmp/jq /usr/bin/jq && sudo chmod +x /usr/bin/jq"
  // install ammonite (scala shell)
  sh """mkdir -p ~/.ammonite && curl -L -o ~/.ammonite/predef.sc https://git.io/v6r5y && mkdir -p ~/.ammonite && curl -L -o ~/.ammonite/predef.sc https://git.io/v6r5y"""
  sh """sudo curl -L -o /usr/local/bin/amm https://github.com/lihaoyi/Ammonite/releases/download/0.8.2/2.12-0.8.2 && sudo chmod +x /usr/local/bin/amm"""
  return this
}

def setBuildInfo(displayName, description) {
  currentBuild.displayName = displayName
  currentBuild.description = description
  return this
}

def checkout_marathon_master() {
  git changelog: false, credentialsId: '4ff09dce-407b-41d3-847a-9e6609dd91b8', poll: false, url: 'git@github.com:mesosphere/marathon.git'
  sh "sudo git clean -fdx"
  sh """git tag | grep phabricator | xargs git tag -d || true """
  return this
}

def clean_git() {
  sh "git checkout master && git branch | grep -v master | xargs git branch -D || true"
  return this
}

// run through compile/lint/docs. Fail if there were format changes after this.
def compile() {
  withEnv(['RUN_DOCKER_INTEGRATION_TESTS=true', 'RUN_MESOS_INTEGRATION_TESTS=true']) {
    sh "sudo -E sbt -Dsbt.log.format=false clean scapegoat doc test:compile"
    sh """if git diff --quiet; then echo 'No format issues detected'; else echo 'Patch has Format Issues'; exit 1; fi"""
  }
}

def test() {
  try {
    timeout(time: 30, unit: 'MINUTES') {
      withEnv(['RUN_DOCKER_INTEGRATION_TESTS=true', 'RUN_MESOS_INTEGRATION_TESTS=true']) {
        STATUS = sh(script: """sudo -E sbt -Dsbt.log.format=false coverage test""", returnStatus: true)
        sh """sudo -E sbt -Dsbt.log.format=false coverageReport"""
        if (STATUS != 0) {
          error "Tests Failed"
        }
      }
    }
  } finally {
    junit allowEmptyResults: true, testResults: 'target/test-reports/**/*.xml'
  }
}

def integration_test() {
  try {
    timeout(time: 30, unit: 'MINUTES') {
      withEnv(['RUN_DOCKER_INTEGRATION_TESTS=true', 'RUN_MESOS_INTEGRATION_TESTS=true']) {
        STATUS = sh(script: """sudo -E sbt -Dsbt.log.format=false '; clean; coverage; integration:test; mesos-simulation/integration:test' """, returnStatus: true)
        sh """sudo -E sbt -Dsbt.log.format=false '; set coverageFailOnMinimum := false; coverageReport'"""
        if (STATUS != 0) {
          error "Integration Tests Failed"
        }
      }
    }
  } finally {
    junit allowEmptyResults: true, testResults: 'target/test-reports/*integration/**/*.xml'
  }
}

def has_unstable_tests() {
  // this line will match, so we have to consider it.
  return sh(script: "git grep \"@UnstableTest\" | wc -l", returnStdout: true).trim() != "1"
}

def unstable_test() {
  try {
    timeout(time: 60, unit: 'MINUTES') {
      withEnv(['RUN_DOCKER_INTEGRATION_TESTS=true', 'RUN_MESOS_INTEGRATION_TESTS=true']) {
        // ignore the status here.
        STATUS = sh(script: "sudo -E sbt -Dsbt.log.format=false clean coverage unstable:test unstable-integration:test", returnStatus: true)
        sh """sudo -E sbt -Dsbt.log.format=false coverageReport"""
        if (STATUS != 0) {
          throw new Exception("Unstable Tests Failed.")
        }
      }
    }
  } finally {
    mark_unstable_results("target/test-reports/unstable-integration target/test-reports/unstable")
    junit allowEmptyResults: true, testResults: 'target/test-reports/unstable-integration/**/*.xml'
    junit allowEmptyResults: true, testResults: 'target/test-reports/unstable/**/*.xml'
  }
}

def assembly() {
  sh "sudo -E sbt assembly"
  sh "sudo bin/build-distribution"
}

def package_binaries() {
  gitVersion = sh(returnStdout: true, script: 'git describe --tags --always').trim()

  parallel(
      "Tar Binaries": {
        sh """sudo tar -czv -f "target/marathon-${gitVersion}.tgz" \
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
        dir("packaging") {
          sh "sudo make all"
        }
      },
      "Build Docker Image": {
        // target is in .dockerignore so we just copy the jar before.
        sh "cp target/*/marathon-assembly-*.jar ."
        mesosVersion = sh(returnStdout: true, script: "sed -n 's/^.*MesosDebian = \"\\(.*\\)\"/\\1/p' <./project/Dependencies.scala").trim()
        docker.build("mesosphere/marathon:${gitVersion}", "--build-arg MESOS_VERSION=${mesosVersion} .")
      }
  )
}

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
  } catch (error) {
    currentBuild.result = 'FAILURE'
    throw error
  } finally {

    // Mark commit with final status
    step([$class: 'GitHubCommitStatusSetter'
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

// !!Important Boilerplate!!
// The external code must return it's contents as an object
return this
