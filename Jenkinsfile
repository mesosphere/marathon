#!/usr/bin/env groovy

def m

ansiColor('gnome-terminal') {
  node('JenkinsMarathonCI-Debian8-2017-04-25') {
    // fetch the file directly from SCM so the job can use it to checkout the rest of the pipeline.
    // TODO: Switch back to master after landing.
    checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: '*/jason/jenkins-submits']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'SparseCheckoutPaths', sparseCheckoutPaths: [[path: 'marathon.groovy']]]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'mesosphere-ci-github', url: 'git@github.com:mesosphere/marathon.git']]]
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
       */
      m = load("marathon.groovy")
    }
    m.build_marathon()
  }
}
