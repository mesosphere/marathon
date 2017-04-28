#!/usr/bin/env groovy

def m

properties([
    parameters([
        string(name: 'MARATHON_GROOVY_BRANCH', defaultValue: 'master', description: 'Branch to locate marathon.groovy from'),
        string(name: 'BRANCH_NAME', description: "Branch to build (for multibranch builds)")
        string(name: 'REVISION_ID', description: 'Phabricator Revision, e.g. D730 => 730, required for Phabricator and Submit Builds'),
        string(name: 'PHID', description: "Phabricator Harbormaster object ID, required for Phabricator Builds"
        string(name: 'DIFF_ID', description: "Diff ID to build (which diff of D730, for example), required for Phabricator Builds")
        string(name: 'TARGET_BRANCH', description: 'Branch to land on (for submit builds)'),
        string(name: 'PUBLISH_SNAPSHOT', description: 'Publish a snapshot to S3. Always true for master/release, enable explicitly for phabricator builds')
    ])
])

ansiColor('gnome-terminal') {
  node('JenkinsMarathonCI-Debian8-2017-04-25') {
    // fetch the file directly from SCM so the job can use it to checkout the rest of the pipeline.
    checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: MARATHON_GROOVY_BRANCH]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'SparseCheckoutPaths', sparseCheckoutPaths: [[path: 'marathon.groovy']]]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'mesosphere-ci-github', url: 'git@github.com:mesosphere/marathon.git']]]
    m = load("marathon.groovy")
    stage("Checkout") {
      m.checkout_marathon()
      /**
       * If any of the content above this line changes, it has to be tested differently:
       *
       * - push a branch starting with "pipelines/"
       * - Clone public-marathon-phabricator-pipeline and base it on your branch instead.
       *   - in public-marathon-phabricator-pipeline, find the most recent run that phabricator initiated,
       *     copy all of the parameters over to your new job and run it. It can help to have
       *     artifact publishing on.
       *
       * - Anything _after_ the next line can be tested through a normal review.
       * - Change MARATHON_GROOVY_BRANCH to your branch when running the build (as a build parameter)
       */
      m = load("marathon.groovy")
    }
    m.build_marathon()
  }
}
// We run the post request on a different node because the AWS nodes do not
// have access to the PostgREST endpoint. See QUALITY-1433 for request of a
// public endpoint.
node("ammonite-0.8.2") {
  stage("Upload") {
    unstash(name: "Test-scoverage")
    sh """amm scripts/post_coverage_data.sc "http://postgrest.marathon.l4lb.thisdcos.directory/marathon_test_coverage" """
    archiveArtifacts artifacts: 'target/test-coverage/scoverage-report/scoverage.csv', allowEmptyArchive: true
  }
}
