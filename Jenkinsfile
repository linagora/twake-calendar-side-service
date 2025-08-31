pipeline {
    agent any

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
                        sh 'git clone https://github.com/linagora/tmail-backend.git'
                    }
                    dir("tmail-backend") {
                        sh 'git submodule init'
                        sh 'git submodule update'
                        sh 'mvn clean install -Dmaven.javadoc.skip=true -DskipTests -T1C'
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
                sh 'mvn -B surefire:test'
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
