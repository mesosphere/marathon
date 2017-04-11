#!/usr/bin/env groovy

// so we can use marathon.groovy later.
def m

ansiColor('gnome-terminal') {
  node('JenkinsMarathonCI-Debian8-2017-03-21') {
    m = load('marathon.groovy')

    // once the diff containing this has been submitted, the if statement above can be
    // removed and a lot of the function calls have to be prefixed with 'm'
    setBuildInfo("D$REVISION_ID($DIFF_ID) #$BUILD_NUMBER", "<a href=\"https://phabricator.mesosphere.com/D$REVISION_ID\">D$REVISION_ID</a>")

    stage("Install Dependencies") {
      install_dependencies()
    }
    stage("Checkout D$REVISION_ID($DIFF_ID)") {
      checkout_marathon_master()
      clean_git()
      phabricator_apply_diff("$PHID", "$BUILD_URL", "$REVISION_ID", "$DIFF_ID")
    }

    m = load('marathon.groovy')
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
      stage("Assemble Binaries") {
        m.assembly()
      }
      stage("Package Binaries") {
        m.package_binaries()
      }
      stage("Unstable Test") {
        if (m.has_unstable_tests()) {
          ignore_error {
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
        } catch (Exception err) {
          m.phabricator("differential.revision.edit", """ transactions: [{type: "comment", value: "\u2717 Build of $DIFF_ID Failed at $BUILD_URL"}], objectIdentifier: "D$REVISION_ID" """)
        }
        m.currentBuild.result = "FAILURE"
      }
    }
  }
}
