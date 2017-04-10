#!/usr/bin/env groovy

/* BEGIN: Block of stuff that we can't have in the library for this job until the patch itself lands - chicken and egg: all in marathon.groovy */

def setBuildInfo(displayName, description) {
  currentBuild.displayName = displayName
  currentBuild.description = description
  return this
}

def install_dependencies() {
  // JQ is broken in the image
  sh "curl -L https://github.com/stedolan/jq/releases/download/jq-1.5/jq-linux64 > /tmp/jq && sudo mv /tmp/jq /usr/bin/jq && sudo chmod +x /usr/bin/jq"
  // install ammonite (scala shell)
  sh """mkdir -p ~/.ammonite && curl -L -o ~/.ammonite/predef.sc https://git.io/v6r5y && mkdir -p ~/.ammonite && curl -L -o ~/.ammonite/predef.sc https://git.io/v6r5y"""
  sh """sudo curl -L -o /usr/local/bin/amm https://github.com/lihaoyi/Ammonite/releases/download/0.8.2/2.12-0.8.2 && sudo chmod +x /usr/local/bin/amm"""
  return this
}

def checkout_marathon_master() {
  git changelog: false, credentialsId: '4ff09dce-407b-41d3-847a-9e6609dd91b8', poll: false, url: 'git@github.com:mesosphere/marathon.git'
  sh "sudo git clean -fdx"
  return this
}

def ignore_error(block) {
  try {
    block
  } catch(err) {

  }
  return this
}

def phabricator_apply_diff(phid, build_url, revision_id, diff_id) {
  phabricator("harbormaster.createartifact", """buildTargetPHID: "$phid", artifactType: "uri", artifactKey: "$build_url", artifactData: { uri: "$build_url", name: "Velocity Results", "ui.external": true }""")
  ignore_error {
    phabricator("differential.revision.edit", """transactions: [{type: "reject", value: false}], objectIdentifier: "D$revision_id" """)
  }
  phabricator("harbormaster.sendmessage", """ buildTargetPHID: "$phid", type: "work" """)
  sh "arc patch --diff $diff_id"
}

def phabricator(method, args) {
  sh "jq -n '{ $args }' | arc call-conduit $method"
  return this
}

def clean_git() {
  sh "git checkout master && git branch | grep -v master | xargs git branch -D || true"
  return this
}

// so we can use marathon.groovy later.
def m
/* END: Block of stuff that can be removed after the patch lands */

ansiColor('gnome-terminal') {
  node('JenkinsMarathonCI-Debian8-2017-03-21') {
    if (fileExists('marathon.groovy')) {
      m = load('marathon.groovy')
    }
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
        if (currentBuild.result != "SUCCESS") {
          icon = "\u26a0"
        }
        m.phabricator("differential.revision.edit", """ transactions: [{type: "accept", value: true}, {type: "comment", value: "$icon Build of $DIFF_ID completed with ${currentBuild.result} at $BUILD_URL"}], objectIdentifier: "D$REVISION_ID" """)
      }
    } catch (Exception err) {
      stage("Publish Results") {
        m.clean_git()
        m.phabricator_test_results("fail")
        m.phabricator("differential.revision.edit", """ transactions: [{type: "reject", value: true}, {type: "comment", value: "\u2717 Build of $DIFF_ID Failed at $BUILD_URL"}], objectIdentifier: "D$REVISION_ID" """)
        m.currentBuild.result = "FAILURE"
      }
    }
  }
}
