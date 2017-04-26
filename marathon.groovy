/**
 * The meat of a Jenkins build
 */

def is_phabricator_build() {
  return (env.REVISION_ID != null && !env.REVISION_ID.isEmpty())
}

def is_submit_request() {
  return env.TARGET_BRANCH != null
}

def is_release_build(gitTag) {
  if (gitTag.contains("SNAPSHOT") || gitTag.contains("g")) {
    return false
  } else if (env.BRANCH_NAME == null) {
    return false
  } else if (env.BRANCH_NAME.startsWith("releases/")) {
    return true
  }
}

def ignore_error(block) {
  try {
    block()
  } catch (ignored) {

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

// Publish the test coverage information into the build.
// When we finally have the publishHtml plugin, this will hopefully work.
def publish_test_coverage(name, dir) {
  //publishHtml([allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true, reportDir: dir, reportFiles: 'index.html', reportName: "$name Coverage"])
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

def is_phabricator_fully_accepted(revision_id) {
  return sh(script: """ jq -n '{ queryKey: "all", constraints: { ids: [$revision_id] }, attachments: { "reviewers" : true } }' |\
                        arc call-conduit differential.revision.search |\
                        jq -e '.response.data[0].attachments.reviewers.reviewers | map(if .status == "rejected" then -100 elif .status == "accepted" then 1 else 0 end) | add | if . >= 3 then true else false end' """,
                        returnStatus: true) == 0
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

def clean_git() {
  sh "sudo git clean -fdx && git tag | grep phabricator | git tag -d"
  return this
}

def previousBuildFailed() {
  def previousResult = currentBuild.rawBuild.getPreviousBuild()?.getResult()
  return !hudson.model.Result.SUCCESS.equals(previousResult)
}

def is_master_or_release() {
  return env.DIFF_ID != "" && ((env.BRANCH_NAME != null && env.BRANCH_NAME.startsWith("releases/")) || env.BRANCH_NAME == "master")
}

/**
 * Wrap block with a stage and a GitHub commit status setter for Github builds.
 *
 * @param label The label for the stage and commit status context.
 * @param block The block to execute in stage.
 */
def stage_with_commit_status(label, block) {
    stage(label) {
      try {
        // Execute steps in stage
        block()
        currentBuild.result = 'SUCCESS'
      } catch (error) {
        currentBuild.result = 'FAILURE'
        throw error
      } finally {
        if (!is_phabricator_build()) {
          // Mark commit with final status
          step([$class: 'GitHubCommitStatusSetter'
              , contextSource: [$class: 'ManuallyEnteredCommitContextSource', context: "Velocity " + label]
          ])
        }
      }
    }
}

def report_success() {
  if (is_phabricator_build() && !is_submit_request()) {
    phabricator_test_results("pass")
    try {
      phabricator("differential.revision.edit", """ transactions: [{type: "accept", value: true}, {type: "comment", value: "\u2714 Build of $DIFF_ID completed at $BUILD_URL"}], objectIdentifier: "D$REVISION_ID" """)
    } catch (Exception err) {
      phabricator("differential.revision.edit", """ transactions: [{type: "comment", value: "\u2174 Build of $DIFF_ID completed at $BUILD_URL"}], objectIdentifier: "D$REVISION_ID" """)
    }
  } else {
    if (is_master_or_release()) {
      if (previousBuildFailed()) {
        slackSend(
            message: "\u2714 branch `${env.BRANCH_NAME}` is green again. (<${env.BUILD_URL}|Open>)",
            color: "good",
            channel: "#marathon-dev",
            tokenCredentialId: "f430eaac-958a-44cb-802a-6a943323a6a8")
      }
    }
    step([$class: 'GitHubCommitStatusSetter'
        , errorHandlers: [[$class: 'ShallowAnyErrorHandler']]
        , contextSource: [$class: 'ManuallyEnteredCommitContextSource', context: "Velocity All"]
        , statusResultSource: [
            $class: 'ConditionalStatusResultSource'
            , results: [
                [$class: 'BetterThanOrEqualBuildResult', result: 'UNSTABLE', state: 'SUCCESS', message: currentBuild.description],
                [$class: 'BetterThanOrEqualBuildResult', result: 'FAILURE', state: 'FAILURE', message: currentBuild.description],
                [$class: 'AnyBuildResult', state: 'FAILURE', message: 'Loophole']
            ]
        ]
    ])
  }
}

def report_failure() {
  if (is_phabricator_build() && !is_submit_request()) {
    phabricator_test_results("fail")
    try {
      phabricator("differential.revision.edit", """ transactions: [{type: "reject", value: true}, {type: "comment", value: "\u2717 Build of $DIFF_ID Failed at $BUILD_URL"}], objectIdentifier: "D$REVISION_ID" """)
    } catch (Exception ignored) {
      phabricator("differential.revision.edit", """ transactions: [{type: "comment", value: "\u2717 Build of $DIFF_ID Failed at $BUILD_URL"}], objectIdentifier: "D$REVISION_ID" """)
    }
  } else {
    if (is_master_or_release()) {
      slackSend(
          message: "\u2718 branch `${env.BRANCH_NAME}` failed in build `${env.BUILD_NUMBER}`. (<${env.BUILD_URL}|Open>)",
          color: "warning",
          channel: "#marathon-dev",
          tokenCredentialId: "f430eaac-958a-44cb-802a-6a943323a6a8")
    }
    step([$class: 'GitHubCommitStatusSetter'
        , errorHandlers: [[$class: 'ShallowAnyErrorHandler']]
        , contextSource: [$class: 'ManuallyEnteredCommitContextSource', context: "Velocity All"]
        , statusResultSource: [
            $class: 'ConditionalStatusResultSource'
            , results: [
                [$class: 'BetterThanOrEqualBuildResult', result: 'UNSTABLE', state: 'SUCCESS', message: currentBuild.description],
                [$class: 'BetterThanOrEqualBuildResult', result: 'FAILURE', state: 'FAILURE', message: currentBuild.description],
                [$class: 'AnyBuildResult', state: 'FAILURE', message: 'Loophole']
            ]
        ]
    ])
  }
}

def report_unstable_tests() {
  if (!is_submit_request()) {
    if (is_phabricator_build()) {
      phabricator("differential.revision.edit", """ transactions: [{type: "comment", value: "\u26a0 Build of $DIFF_ID has Unstable Tests at $BUILD_URL"}], objectIdentifier: "D$REVISION_ID" """)
    } else if (is_master_or_release()) {
      slackSend(message: "\u26a0 branch `${env.BRANCH_NAME}` has unstable tests in build `${env.BUILD_NUMBER}`. (<${env.BUILD_URL}|Open>)",
          color: "danger",
          channel: "#marathon-dev",
          tokenCredentialId: "f430eaac-958a-44cb-802a-6a943323a6a8")
    } else {
      // TODO: Can we comment on PRs?
    }
  }
}

def setBuildInfo(displayName, description) {
  currentBuild.displayName = displayName
  currentBuild.description = description
  return this
}

// Note: If any of this content changes, you need to follow the testing process listed in Jenkinsfile.
def checkout_marathon() {
  if (is_phabricator_build()) {
    if (is_submit_request()) {
      setBuildInfo("D$REVISION_ID -> $TARGET_BRANCH #$BUILD_NUMBER", "<a href=\"https://phabricator.mesosphere.com/D$REVISION_ID\">D$REVISION_ID</a>")
      git branch: TARGET_BRANCH, changelog: false, credentialsId: '4ff09dce-407b-41d3-847a-9e6609dd91b8', poll: false, url: 'git@github.com:mesosphere/marathon.git'
      if (!is_phabricator_fully_accepted(REVISION_ID)) {
        error "Patch is not fully accepted, required: 2 accepts + jenkins and 0 rejects."
      }
      sh "arc patch --nobranch $REVISION_ID"
      configFileProvider([configFile(fileId: 'a7a9bcc5-5db0-40c3-a8dd-6ab52e2ccadd', targetLocation: '/home/admin/.gnupg/privatekey.tmp')]) {
        // Don't fail if the key is already imported.
        sh "gpg --import /home/admin/.gnupg/privatekey.tmp || true"
      }
      sshagent(['mesosphere-ci-github']) {
        sh '''git config user.name "Mesosphere CI Robot" && \
              git config user.email "mesosphere-ci@users.noreply.github.com" &&\
              git config user.signingkey 32725FF3 &&\
              git commit -S --amend --signoff --no-edit &&
              git push origin $(git rev-parse HEAD)'''
      }
      clean_git()
    } else {
      setBuildInfo("D$REVISION_ID($DIFF_ID) #$BUILD_NUMBER", "<a href=\"https://phabricator.mesosphere.com/D$REVISION_ID\">D$REVISION_ID</a>")
      git changelog: false, credentialsId: '4ff09dce-407b-41d3-847a-9e6609dd91b8', poll: false, url: 'git@github.com:mesosphere/marathon.git'
      sh """git checkout master && git branch | grep -v master | xargs git branch -D || true"""
      phabricator_apply_diff("$PHID", "$BUILD_URL", "$REVISION_ID", "$DIFF_ID")
      clean_git()
    }
  } else {
    checkout scm
    gitCommit = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
    shortCommit = gitCommit.take(8)
    currentBuild.displayName = "#${env.BUILD_NUMBER}: ${env.BRANCH_NAME} ${shortCommit}"
    clean_git()
  }
}

// run through compile/lint/docs. Fail if there were format changes after this.
def compile() {
  try {
    withCredentials([file(credentialsId: 'DOT_M2_SETTINGS', variable: 'DOT_M2_SETTINGS')]) {
      withEnv(['RUN_DOCKER_INTEGRATION_TESTS=true', 'RUN_MESOS_INTEGRATION_TESTS=true']) {
        sh "sudo -E sbt -Dsbt.log.format=false clean scapegoat doc test:compile"
        sh """if git diff --quiet; then echo 'No format issues detected'; else echo 'Patch has Format Issues'; exit 1; fi"""
      }
    }
  } finally {
    archiveArtifacts artifacts: 'target/**/scapegoat-report/scapegoat.html', allowEmptyArchive: true
  }
}

def test() {
  try {
    timeout(time: 30, unit: 'MINUTES') {
      withCredentials([file(credentialsId: 'DOT_M2_SETTINGS', variable: 'DOT_M2_SETTINGS')]) {
        withEnv(['RUN_DOCKER_INTEGRATION_TESTS=true', 'RUN_MESOS_INTEGRATION_TESTS=true']) {
          sh """sudo -E sbt -Dsbt.log.format=false '; clean; coverage; testWithCoverageReport' """
        }
      }
    }
  } finally {
    junit allowEmptyResults: true, testResults: 'target/test-reports/**/*.xml'
    publish_test_coverage("Test", "target/test-coverage")
  }
}

def integration_test() {
  try {
    timeout(time: 60, unit: 'MINUTES') {
      withCredentials([file(credentialsId: 'DOT_M2_SETTINGS', variable: 'DOT_M2_SETTINGS')]) {
        withEnv(['RUN_DOCKER_INTEGRATION_TESTS=true', 'RUN_MESOS_INTEGRATION_TESTS=true']) {
          sh """sudo -E sbt -Dsbt.log.format=false '; clean; coverage; integration:testWithCoverageReport; serial-integration:testWithCoverageReport' """
        }
      }
    }
  } finally {
    junit allowEmptyResults: true, testResults: 'target/test-reports/*integration/**/*.xml'
    publish_test_coverage("integration test", "target/integration-coverage")
  }
}

def has_unstable_tests() {
  // this line will match, so we have to consider it.
  return sh(script: "git grep \"@UnstableTest\" | wc -l", returnStdout: true).trim() != "1"
}

def unstable_test() {
  try {
    timeout(time: 60, unit: 'MINUTES') {
      withCredentials([file(credentialsId: 'DOT_M2_SETTINGS', variable: 'DOT_M2_SETTINGS')]) {
        withEnv(['RUN_DOCKER_INTEGRATION_TESTS=true', 'RUN_MESOS_INTEGRATION_TESTS=true']) {
          sh "sudo -E sbt -Dsbt.log.format=false '; clean; coverage; unstable:testWithCoverageReport; unstable-integration:testWithCoverageReport' "
        }
      }
    }
  } catch (Exception err) {
    report_unstable_tests()
  } finally {
    mark_unstable_results("target/test-reports/unstable-integration target/test-reports/unstable")
    junit allowEmptyResults: true, testResults: 'target/test-reports/unstable-integration/**/*.xml'
    junit allowEmptyResults: true, testResults: 'target/test-reports/unstable/**/*.xml'
    publish_test_coverage("Unstable Test", "target/unstable-coverage")
    publish_test_coverage("Unstable Integration Test", "target/unstable-integration-coverage")
  }
}

def publish_to_s3(gitTag) {
    storageClass = "STANDARD_IA"
    // TODO: we could use marathon-artifacts for both profile and buckets, but we would
    // need to either setup a bucket policy for public-read on the s3://marathon-artifacts/snapshots
    // We should probably prefer downloads as this allows us to share snapshot builds
    // with anyone. The directory listing isn't public anyways.
    profile = "aws-production"
    bucket = "downloads.mesosphere.io/marathon/snapshots"
    region = "us-east-1"
    upload_on_failure = true
    // manage_artifacts == true will put the artifacts in snapshots/job/{pipelinename}/{branch/?}/{build_number}
    manage_artifacts = is_phabricator_build()
    if (is_release_build(gitTag)) {
      storageClass = "STANDARD"
      bucket = "downloads.mesosphere.io/marathon/${gitTag}"
      upload_on_failure = false
      manage_artifacts = false
    }
    sh "sudo sh -c 'sha1sum target/universal/marathon-${gitTag}.txz > target/universal/marathon-${gitTag}.txz.sha1'"
    sh "sudo sh -c 'sha1sum target/universal/marathon-${gitTag}.zip > target/universal/marathon-${gitTag}.zip.sha1'"
    step([
        $class: 'S3BucketPublisher',
        entries: [
          [
              sourceFile: "target/universal/marathon-*.txz",
              bucket: bucket,
              selectedRegion: region,
              noUploadOnFailure: upload_on_failure,
              managedArtifacts: manage_artifacts,
              flatten: true,
              showDirectlyInBrowser: false,
              keepForever: true,
              storageClass: storageClass,
          ],
          [
              sourceFile: "target/universal/marathon-*.txz.sha1",
              bucket: bucket,
              selectedRegion: region,
              noUploadOnFailure: upload_on_failure,
              managedArtifacts: manage_artifacts,
              flatten: true,
              showDirectlyInBrowser: false,
              keepForever: true,
              storageClass: storageClass,
          ],
          [
              sourceFile: "target/universal/marathon-*.zip",
              bucket: bucket,
              selectedRegion: region,
              noUploadOnFailure: upload_on_failure,
              managedArtifacts: manage_artifacts,
              flatten: true,
              showDirectlyInBrowser: false,
              keepForever: true,
              storageClass: storageClass,
          ],
          [
              sourceFile: "target/universal/marathon-*.zip.sha1",
              bucket: bucket,
              selectedRegion: region,
              noUploadOnFailure: upload_on_failure,
              managedArtifacts: manage_artifacts,
              flatten: true,
              showDirectlyInBrowser: false,
              keepForever: true,
              storageClass: storageClass,
          ],
        ],
        profileName: profile,
        dontWaitForConcurrentBuildCompletion: false,
        consoleLogLevel: 'INFO',
        pluginFailureResultConstraint: 'FAILURE'
    ])}

def publish_artifacts() {
  gitTag = sh(returnStdout: true, script: "git describe --tags --always").trim().replaceFirst("v", "")

  // Only create latest-dev snapshot for master.
  // TODO: Docker 1.12 doesn't support tag -f and the jenkins docker plugin still passes it in.
  if (env.BRANCH_NAME == "master" && !is_phabricator_build()) {
    sh "docker tag mesosphere/marathon:${gitTag} mesosphere/marathon:latest-dev"
    docker.withRegistry("https://index.docker.io/v1/", "docker-hub-credentials") {
      sh "docker push mesosphere/marathon:latest-dev"
    }
  } else if (env.PUBLISH_SNAPSHOT == "true" || (is_release_build(gitTag) && !is_phabricator_build())) {
    docker.withRegistry("https://index.docker.io/v1/", "docker-hub-credentials") {
      sh "docker push mesosphere/marathon:${gitTag}"
    }
  }


  if (env.PUBLISH_SNAPSHOT == "true" || is_master_or_release()) {
    publish_to_s3(gitTag)

    sshagent(credentials: ['0f7ec9c9-99b2-4797-9ed5-625572d5931d']) {
      // we rsync a directory first, then copy over the binaries into specific folders so
      // that the cron job won't try to publish half-uploaded RPMs/DEBs
      sh """ssh -o StrictHostKeyChecking=no pkgmaintainer@repo1.hw.ca1.mesosphere.com "mkdir -p ~/repo/incoming/marathon-${gitTag}" """
      sh "rsync -avzP target/packages/*${gitTag}* target/packages/*.rpm pkgmaintainer@repo1.hw.ca1.mesosphere.com:~/repo/incoming/marathon-${gitTag}"
      sh """ssh -o StrictHostKeyChecking=no -o BatchMode=yes pkgmaintainer@repo1.hw.ca1.mesosphere.com "env GIT_TAG=${gitTag} bash -s --" < scripts/publish_packages.sh """
      sh """ssh -o StrictHostKeyChecking=no -o BatchMode=yes pkgmaintainer@repo1.hw.ca1.mesosphere.com "rm -rf ~/repo/incoming/marathon-${gitTag}" """
    }
  }
  return this
}

def package_binaries() {
  sh("sudo rm -f target/packages/*")
  sh("sudo sbt clean packageAll")
  return this
}

def archive_artifacts() {
  archiveArtifacts artifacts: 'target/**/classes/**', allowEmptyArchive: true
  archiveArtifacts artifacts: 'target/universal/marathon-*.zip', allowEmptyArchive: false
  archiveArtifacts artifacts: 'target/universal/marathon-*.txz', allowEmptyArchive: false
  archiveArtifacts artifacts: "target/packages/*", allowEmptyArchive: false
}

def build_marathon() {
  try {
    stage("Kill Junk") {
      kill_junk()
    }
    stage("Install Mesos") {
      install_mesos()
    }
    stage_with_commit_status("1. Compile") {
      compile()
    }
    stage_with_commit_status("2. Test") {
      test()
    }
    stage_with_commit_status("3. Integration Test") {
      integration_test()
    }
    stage_with_commit_status("4. Package Binaries") {
      package_binaries()
    }
    stage_with_commit_status("5. Archive Artifacts") {
      archive_artifacts()
    }
    stage_with_commit_status("6. Publish Binaries") {
      publish_artifacts()
    }
    stage_with_commit_status("7. Unstable Tests") {
      if (has_unstable_tests()) {
        unstable_test()
      } else {
        echo "\u2714 No Unstable Tests!"
      }
    }
    if (is_submit_request()) {
      stage("Merge Patch") {
        sshagent(['mesosphere-ci-github']) {
          sh "git push origin $TARGET_BRANCH"
        }
      }
    }
    report_success()
  } catch (Exception err) {
    report_failure()
    throw err
  }
}

// !!Important Boilerplate!!
// The external code must return it's contents as an object
return this
