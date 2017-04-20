#!/usr/bin/env groovy

/*
 If you modify this file, you can't quite test it with the regular pipeline, instead,
 perform the following:

 - Clone the current public-marathon-phabricator-pipeline as <user>-marathon-d<rev>
 - Push your branch and set the job to pull from your branch instead of master.
 - Switch the checkout stage to use "checkout scm" and copy the build parameters
   from a previous run of public-marathon-phabricator-pipeline for your diff. Now you
   can test whenever you'd like just by hitting rebuild.
   e.g.
   {{{
   stage("Checkout D$REVISION_ID($DIFF_ID)") {
     checkout scm
     m = load('marathon.groovy')
   }
   }}}

 - Don't forget to push/update and undo the last piece before you submit.
 - If you need to test it against master too, name your branch with pipelines/ as a prefix.
 */

/* BEGIN: Things defined in marathon.groovy that have to be duplicated because we don't have marathon.groovy available at the time they are needed. */
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
/* END */

// so we can use marathon.groovy later.
def m

ansiColor('gnome-terminal') {
  node('JenkinsMarathonCI-Debian8-2017-03-21') {
    try {
      m = load('marathon.groovy')
    } catch (err) {}

    // once the diff containing this has been submitted, the if statement above can be
    // removed and a lot of the function calls have to be prefixed with 'm'
    setBuildInfo("D$REVISION_ID($DIFF_ID) #$BUILD_NUMBER", "<a href=\"https://phabricator.mesosphere.com/D$REVISION_ID\">D$REVISION_ID</a>")

    stage("Install Dependencies") {
      install_dependencies()
    }
    stage("Checkout D$REVISION_ID($DIFF_ID)") {
      checkout_marathon_master()
      m = load('marathon.groovy')
      m.clean_git()
      m.phabricator_apply_diff("$PHID", "$BUILD_URL", "$REVISION_ID", "$DIFF_ID")
      // load it again in case it changed.
      m = load('marathon.groovy')
    }

    if (m == null) {
      error "Jenkins doesn't know how to load stuff."
    }
    try {
      stage("Install Mesos") {
        m.install_mesos()
      }
      stage("Kill junk processes") {
        m.kill_junk()
      }
      stage("Compile") {
        m.compile()
      }
      stage("Test") {
        try {
          m.test()
        } finally {
          m.phabricator_convert_test_coverage()
          m.publish_test_coverage("Test Coverage")
        }
      }
      stage("Integration Test") {
        try {
          m.integration_test()
        } finally {
          m.phabricator_convert_test_coverage()
          m.publish_test_coverage("Integration Test Coverage")
        }
      }
      stage("Package Binaries") {
        m.package_binaries()
      }
      stage("Publish Artifacts") {
        if (env.PUBLISH_SNAPSHOT == "true") {
          m.publish_artifacts()
        } else {
          echo "Skipping Artifact Publishing, snapshot publishing not requested."
        }
      }
      stage("Unstable Test") {
        if (m.has_unstable_tests()) {
          m.ignore_error {
            m.unstable_test()
          }
          m.phabricator_convert_test_coverage()
          m.publish_test_coverage("Unstable Test Coverage")
        } else {
          echo "No Unstable Tests \u2713"
        }
      }
      stage("Publish Results") {
        m.clean_git()
        m.phabricator_test_results("pass")
        icon = "\u221a"
        try {
          m.phabricator("differential.revision.edit", """ transactions: [{type: "accept", value: true}, {type: "comment", value: "$icon Build of $DIFF_ID completed at $BUILD_URL"}], objectIdentifier: "D$REVISION_ID" """)
        } catch (Exception err) {
          m.phabricator("differential.revision.edit", """ transactions: [{type: "comment", value: "$icon Build of $DIFF_ID completed at $BUILD_URL"}], objectIdentifier: "D$REVISION_ID" """)
        }
      }
    } catch (Exception err) {
      stage("Publish Results") {
        m.clean_git()
        m.phabricator_test_results("fail")
        try {
          m.phabricator("differential.revision.edit", """ transactions: [{type: "reject", value: true}, {type: "comment", value: "\u2717 Build of $DIFF_ID Failed at $BUILD_URL"}], objectIdentifier: "D$REVISION_ID" """)
        } catch (Exception ignored) {
          m.phabricator("differential.revision.edit", """ transactions: [{type: "comment", value: "\u2717 Build of $DIFF_ID Failed at $BUILD_URL"}], objectIdentifier: "D$REVISION_ID" """)
        }
        m.currentBuild.result = "FAILURE"
      }
    }
  }
}
