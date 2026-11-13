import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

def failedTestSummary = ''

// app runs the full-stack tests with two existing Surefire forks. Run the remaining
// modules sequentially alongside it, without adding reactor or in-JVM test parallelism.
def runTestBranch(String branch, String projects) {
    withEnv(["TEST_PROJECTS=${projects}", "RESOURCES_FILE=${env.WORKSPACE}/ci-logs/test-resources-${branch}.txt"]) {
        sh label: 'Run Maven tests', script: '''
            mkdir -p ci-logs
            (
                set +x
                free -m | head -n 1
                while true; do
                    echo "$(date +%T) $(free -m | sed -n 2p) load $(cut -d ' ' -f 1-3 /proc/loadavg)"
                    sleep 30
                done
            ) > "$RESOURCES_FILE" 2>&1 &
            MONITOR_PID=$!
            trap 'kill "$MONITOR_PID" 2>/dev/null || true' 0
            # Both Maven builds read the artifacts installed by Compile and share ~/.m2.
            mvn -B -Dapi.version=1.43 surefire:test -pl "$TEST_PROJECTS" \
                -Daether.syncContext.named.factory=file-lock -Daether.syncContext.named.nameMapper=file-gav
        '''
    }
}

pipeline {
    agent {
        label 'heavy'
    }

    tools {
        jdk 'jdk_25'
    }

    environment {
        DOCKER_HUB_CREDENTIAL = credentials('dockerHub')
    }

    options {
        // Configure an overall timeout for the build.
        timeout(time: 3, unit: 'HOURS')
        disableConcurrentBuilds()
    }
    
    stages {
        stage('Build tmail backend first') {
            steps {
                sh 'mkdir .build'
                dir(".build") {
                    withCredentials([gitUsernamePassword(credentialsId: 'github')]) {
                        sh 'git clone --depth 1 https://github.com/linagora/tmail-backend.git'
                    }
                    dir("tmail-backend") {
                        sh 'git submodule update --init --depth 1 james-project'
                        script {
                            def tmailBackendModules = [
                                ':tmail-backend-parent',
                                ':james-project',
                                ':james-server-guice',
                                ':logback-json-classic',
                                ':logback-jackson',
                                ':tmail-saas-rabbitmq',
                                ':jmap-extensions',
                                ':jmap-extensions-opensearch',
                                ':tmail-event-bus-redis',
                                ':apache-james-backends-opensearch',
                                ':apache-james-backends-rabbitmq',
                                ':apache-james-backends-redis',
                                ':james-server-data-ldap',
                                ':james-server-data-memory',
                                ':james-server-guice-common',
                                ':james-server-guice-data-ldap',
                                ':james-server-guice-opensearch',
                                ':james-server-guice-webadmin',
                                ':james-server-testing',
                                ':james-server-webadmin-data',
                                ':mock-smtp-server',
                                ':queue-rabbitmq-guice',
                                ':testing-base',
                                ':james-core',
                                ':james-server-core',
                                ':james-server-jwt',
                                ':metrics-api',
                                ':metrics-tests',
                                ':event-bus-api',
                                ':james-server-jmap',
                                ':james-server-guice-configuration',
                                ':james-server-webadmin-core',
                                ':apache-james-mailbox-store',
                                ':james-server-data-api',
                                ':james-server-util'
                            ].join(',')

                            sh "mvn clean install -Dmaven.javadoc.skip=true -DskipTests -T1C -pl ${tmailBackendModules} -am"
                        }
                    }
                }
            }
        }
        stage('Compile') {
            steps {
                sh 'mvn clean install -Dmaven.javadoc.skip=true -DskipTests -T1C'
            }
        }
        stage('Parallel tests') {
            failFast true
            parallel {
                stage('Tests: app') {
                    steps {
                        runTestBranch('app', 'app')
                    }
                }
                stage('Tests: general') {
                    steps {
                        // The complement includes new modules automatically; do not use -am,
                        // which would retest dependencies and write reports in both branches.
                        runTestBranch('modules', '!app')
                    }
                }
            }
            post {
                always {
                    archiveArtifacts artifacts: 'ci-logs/test-resources-*.txt', allowEmptyArchive: true, fingerprint: false
                    junit(testResults: '**/surefire-reports/*.xml', allowEmptyResults: false)
                }
                failure {
                    archiveArtifacts artifacts: '**/target/test-run.log' , fingerprint: true
                    archiveArtifacts artifacts: '**/surefire-reports/*' , fingerprint: true
                }
            }
        }
        stage('Deliver Docker images') {        
          when {
            anyOf {
              branch 'main'
              buildingTag()
            }
          }
          steps {
            script {
              env.DOCKER_TAG = 'branch-master'
              if (env.TAG_NAME) {
                env.DOCKER_TAG = env.TAG_NAME
              }

              echo "Docker tag: ${env.DOCKER_TAG}"

              sh 'docker load -i app/target/jib-image.tar'
              sh 'docker tag linagora/twake-calendar-side-service:latest linagora/twake-calendar-side-service:$DOCKER_TAG'
              sh 'docker login -u $DOCKER_HUB_CREDENTIAL_USR -p $DOCKER_HUB_CREDENTIAL_PSW'
              sh 'docker push linagora/twake-calendar-side-service:$DOCKER_TAG'
            }
          }
        }

    }
    post {
        always {
            script {
                try {
                    if (env.CHANGE_ID && currentBuild.currentResult != 'SUCCESS') {
                        // Read reports before deleteDir; retain only Markdown for the final notification.
                        try {
                            failedTestSummary = sh(label: 'Summarize failed tests', returnStdout: true,
                                script: 'python3 ci/summarize-test-failures.py').trim()
                        } catch (FlowInterruptedException interrupted) {
                            throw interrupted
                        } catch (Exception ignored) {
                            failedTestSummary = '> Failed test details are unavailable. See the build log.'
                            echo 'WARNING: Could not summarize failed tests.'
                        }
                    }
                } finally {
                    deleteDir() /* clean up our workspace even if summary collection is interrupted */
                }
            }
        }
        cleanup {
            script {
                if (env.CHANGE_ID) {
                    // Report the final result after all stages and other post actions.
                    try {
                        def result = currentBuild.currentResult
                        def icons = [SUCCESS: '✅', FAILURE: '❌', UNSTABLE: '⚠️', ABORTED: '⏹️', NOT_BUILT: '⏭️']
                        def body = "${icons[result] ?: 'ℹ️'} Jenkins pipeline finished: **${result}**."
                        if (failedTestSummary) {
                            body += "\n\n${failedTestSummary}"
                        }
                        withEnv(["PR_COMMENT_JSON=${groovy.json.JsonOutput.toJson([body: body])}"]) {
                            withCredentials([string(credentialsId: 'github-calendar-bot', variable: 'GITHUB_TOKEN')]) {
                                def status = sh(label: 'Comment Jenkins result on PR', returnStatus: true, script: '''
                                    set +x
                                    printf '%s' "$PR_COMMENT_JSON" | curl --fail --silent --show-error \\
                                        --connect-timeout 10 --max-time 30 -o /dev/null -X POST \\
                                        -H "Authorization: Bearer $GITHUB_TOKEN" \\
                                        -H "Content-Type: application/json" \\
                                        --data-binary @- \\
                                        "https://api.github.com/repos/linagora/twake-calendar-side-service/issues/$CHANGE_ID/comments"
                                ''')
                                if (status != 0) {
                                    echo "WARNING: Could not post Jenkins result to PR (curl exit ${status})."
                                }
                            }
                        }
                    } catch (FlowInterruptedException interrupted) {
                        throw interrupted
                    } catch (Exception ignored) {
                        echo 'WARNING: Could not post Jenkins result to PR.'
                    }
                }
            }
        }
    }
}
