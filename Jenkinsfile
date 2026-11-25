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

    // No pipeline-level credentials, see withGithubCredential and withDockerHubLogin.

    options {
        // Configure an overall timeout for the build.
        timeout(time: 3, unit: 'HOURS')
        disableConcurrentBuilds()
    }
    
    stages {
        stage('Authorize fork') {
            // A fork pull request runs no code of its own before an org member vouches for its head
            // commit, see authorizeFork
            when {
                changeRequest()
                expression { env.CHANGE_FORK != null }
            }
            steps {
                script {
                    authorizeFork()
                }
            }
        }
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
              withDockerHubLogin {
                sh 'docker push linagora/twake-calendar-side-service:$DOCKER_TAG'
              }
            }
          }
        }

    }
    post {
        always {
            script {
                try {
                    if (env.CHANGE_ID && currentBuild.currentResult != 'SUCCESS' && mayRunChangeCode()) {
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

def githubRepository() {
  return 'linagora/twake-calendar-side-service'
}

// ---- Fork authorization and credentials. The same block is in the Jenkinsfiles of
// twake-contacts-frontend, twake-calendar-frontend, twake-calendar-side-service, webadmin-proxy,
// tmail-flutter, esn-sabre, tmail-backend and twake-mail-admin: change it in all of them. Only
// githubRepository() differs.
//
// No credential is bound at pipeline level: every stage runs code of the checked out sources (build
// plugins, package scripts, tests, scripts/), so secrets are bound only around the steps that need
// them, see withGithubCredential and withDockerHubLogin.

// A fork pull request runs no code of its own before an org member vouches for its head commit: the
// owner of the fork is a linagora member, or a member approved that very commit (see
// memberReviewerOf). An approval names one commit, so a commit pushed after it is not built until a
// member approves it again.
def authorizeFork() {
  withGithubCredential {
    // CHANGE_FORK is 'owner', or 'owner/repository' for a fork renamed on GitHub
    def forkOwner = env.CHANGE_FORK.tokenize('/')[0]
    // Jenkins may set CHANGE_FORK to 'linagora' for a branch of the repository itself: only someone
    // with write access pushes there, and an organization is not a member of itself.
    if (forkOwner == 'linagora' || isOrgMember(forkOwner)) {
      echo "Fork owner '${forkOwner}' is a linagora org member, proceeding."
      env.FORK_AUTHORIZED = 'true'
      return
    }
    def headSha = pullRequestHead()
    def approver = memberReviewerOf(headSha)
    if (approver) {
      echo "Commit ${headSha} approved by linagora member '${approver}', proceeding."
      requireApprovedCheckout(headSha)
      env.FORK_AUTHORIZED = 'true'
      return
    }
    error("Fork owner '${forkOwner}' is not a linagora org member and no member reviewed commit ${headSha}. " +
      "A linagora member must approve the pull request on this commit, or comment 'Build this please ${headSha}', " +
      "then re-run the build.")
  }
}

// Whether the build may run code of the checked out sources, for the post actions that run even
// when the fork authorization failed
def mayRunChangeCode() {
  return env.CHANGE_FORK == null || env.FORK_AUTHORIZED == 'true'
}

// The head commit of the pull request, as GitHub reports it now. It need not be the commit Jenkins
// checked out: the contributor can push between the checkout and this call. See
// requireApprovedCheckout.
def pullRequestHead() {
  return githubApi("pulls/${env.CHANGE_ID}").head.sha
}

// Fails the build unless what Jenkins checked out is the approved commit: HEAD itself, or the merge
// Jenkins made of the pull request revision it pinned with the target branch, whose first parent is
// that revision. The pinned revision is the one Jenkins reports the build status on, so the status
// always names an approved commit. The contributor chooses the parents of the commits they push, so
// a parent of HEAD is trusted only when the pinned revision, fetched as origin/<BRANCH_NAME>, is it.
def requireApprovedCheckout(String sha) {
  withEnv(["APPROVED_SHA=${sha}"]) {
    sh '''
      head=$(git rev-parse HEAD)
      if [ "$head" = "$APPROVED_SHA" ]; then
        exit 0
      fi
      pinned=$(git rev-parse -q --verify "refs/remotes/origin/$BRANCH_NAME^{commit}" || true)
      first=$(git rev-parse -q --verify "HEAD^1" || true)
      if [ "$pinned" = "$APPROVED_SHA" ] && [ "$first" = "$APPROVED_SHA" ]; then
        echo "Building $head, the merge of the approved commit $APPROVED_SHA with the target branch"
        exit 0
      fi
      echo "The pull request head moved to $APPROVED_SHA since the checkout of $head (pull request revision ${pinned:-unknown}): re-run the build." >&2
      exit 1
    '''
  }
}

// The login of a linagora member who vouched for the given commit: an approving review of it, a review
// 'Build this please' of it, or a comment 'Build this please <sha>' naming its full id. A prefix is not
// accepted: the contributor writes the commits, and can craft one sharing a short prefix with the
// reviewed one. GitHub lists reviews and comments oldest first, 100 per page at most: every page is read.
def memberReviewerOf(String sha) {
  def page = 1
  while (true) {
    def reviews = githubApi("pulls/${env.CHANGE_ID}/reviews?per_page=100&page=${page}")
    for (review in reviews) {
      def approves = review.state == 'APPROVED' ||
        (review.state != 'DISMISSED' && "${review.body}".trim() == 'Build this please')
      if (review.commit_id == sha && approves && isOrgMember(review.user.login)) {
        return review.user.login
      }
    }
    if (reviews.size() < 100) {
      break
    }
    page++
  }
  def approval = "build this please ${sha}".toString()
  page = 1
  while (true) {
    def comments = githubApi("issues/${env.CHANGE_ID}/comments?per_page=100&page=${page}")
    for (comment in comments) {
      def body = "${comment.body}".trim().toLowerCase().replaceAll('\\s+', ' ')
      if (body == approval && isOrgMember(comment.user.login)) {
        return comment.user.login
      }
    }
    if (comments.size() < 100) {
      return null
    }
    page++
  }
}

def isOrgMember(String login) {
  def status
  withEnv(["GITHUB_LOGIN=${login}"]) {
    status = sh(
      script: 'curl -s -o /dev/null -w "%{http_code}" -H "Authorization: token $GITHUB_CREDENTIAL_PSW" ' +
        '"https://api.github.com/orgs/linagora/members/$GITHUB_LOGIN"',
      returnStdout: true
    ).trim()
  }
  echo "GitHub org membership check returned HTTP ${status} for '${login}'"
  if (status == '204') {
    return true
  }
  if (status == '404') {
    return false
  }
  error("GitHub API error ${status} while checking the membership of '${login}'")
}

def githubApi(String path) {
  def json
  withEnv(["GITHUB_API_PATH=${path}", "GITHUB_API_REPOSITORY=${githubRepository()}"]) {
    json = sh(
      script: 'curl -sf -H "Authorization: token $GITHUB_CREDENTIAL_PSW" ' +
        '"https://api.github.com/repos/$GITHUB_API_REPOSITORY/$GITHUB_API_PATH"',
      returnStdout: true
    )
  }
  return readJSON(text: json)
}

// Binds the GitHub token as GITHUB_CREDENTIAL_USR / GITHUB_CREDENTIAL_PSW around the given steps only
def withGithubCredential(Closure body) {
  withCredentials([usernamePassword(credentialsId: 'github',
      usernameVariable: 'GITHUB_CREDENTIAL_USR', passwordVariable: 'GITHUB_CREDENTIAL_PSW')]) {
    body()
  }
}

// Logs in Docker Hub in a throwaway Docker config deleted afterwards, so that the credential never
// stays on the agent where a later build could read it
def withDockerHubLogin(Closure body) {
  withEnv(["DOCKER_CONFIG=${pwd(tmp: true)}/docker-config"]) {
    try {
      withCredentials([usernamePassword(credentialsId: 'dockerHub',
          usernameVariable: 'DOCKER_HUB_CREDENTIAL_USR', passwordVariable: 'DOCKER_HUB_CREDENTIAL_PSW')]) {
        sh 'mkdir -p "$DOCKER_CONFIG" && printf \'%s\' "$DOCKER_HUB_CREDENTIAL_PSW" | docker login -u "$DOCKER_HUB_CREDENTIAL_USR" --password-stdin'
      }
      body()
    } finally {
      sh 'docker logout || true; rm -rf "$DOCKER_CONFIG"'
    }
  }
}
