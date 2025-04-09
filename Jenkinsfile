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
                        sh 'git clone https://github.com/linagora/twake-calendar-side-service.git'
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
    }
    post {
        always {
            deleteDir() /* clean up our workspace */
        }
    }
}
