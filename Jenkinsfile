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
                                ':tmail-saas-rabbitmq',
                                ':jmap-extensions',
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
        stage('Test') {
            steps {
                sh 'mvn -B -Dapi.version=1.43 surefire:test'
            }
            post {
                always {
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
            deleteDir() /* clean up our workspace */
        }
    }
}
