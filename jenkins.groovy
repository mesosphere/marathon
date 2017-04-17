def basename = "${REPO_NAMESPACE}__${REPO_NAME}__${UNIQ_ID}"

if ("${CREATE_JOBS}".toBoolean()) {
    job("${basename}__run-tests") {
        description "Run the ${REPO_NAME} unit tests."
        label "mesos-dind"

        // Pass parameters from the seed job to generated jobs
        parameters {
            stringParam("GIT_SHA", "${GIT_SHA}")
            stringParam("STATUS_CALLBACK_URL", "${STATUS_CALLBACK_URL}")
        }

        scm {
            git {
                remote {
                    url("${REPO_URL}")
                }
                branch("${GIT_SHA}")
                createTag(false)
            }
        }

        steps {
            shell '''
            bash -x ./bin/run-tests.sh ${GIT_SHA}

            if [[ $? == 0 ]]; then
                BUILD_STATUS=success
            else
                BUILD_STATUS=failure
            fi

            curl -sX POST "${STATUS_CALLBACK_URL}${BUILD_STATUS}"
            '''.stripIndent().trim()
        }

        wrappers {
            colorizeOutput()
        }

        configure { project ->
            project / publishers << 'jenkins.plugins.logstash.LogstashNotifier' {
                maxLines(-1)
                failBuild(true)
            }
        }

        queue("${basename}__run-tests")
    }
}
