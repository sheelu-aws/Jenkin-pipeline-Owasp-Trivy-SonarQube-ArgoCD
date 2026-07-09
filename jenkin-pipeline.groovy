pipeline {
    agent any

    tools {
        maven 'Maven3'
        jdk 'JDK17'
    }

    environment {
        IMAGE_NAME = "dockerhubusername/java-app"
        IMAGE_TAG = "${BUILD_NUMBER}"

        GIT_REPO = "https://github.com/dockerhubusername/java-app.git"
        MANIFEST_REPO = "https://github.com/dockerhubusername/k8s-manifests.git"

        DOCKER_CREDENTIALS = "dockerhub-creds"
        GIT_CREDENTIALS = "github-creds"
        SONARQUBE = "SonarQube"
    }

    stages {

        stage('Checkout Source') {
            steps {
                git branch: 'main',
                credentialsId: "${GIT_CREDENTIALS}",
                url: "${GIT_REPO}"
            }
        }

        stage('Compile') {
            steps {
                sh 'mvn clean compile'
            }
        }

        stage('Unit Test') {
            steps {
                sh 'mvn test'
            }
        }

        stage('SonarQube Analysis') {
            steps {
                withSonarQubeEnv("${SONARQUBE}") {
                    sh '''
                    mvn sonar:sonar \
                    -Dsonar.projectKey=java-app \
                    -Dsonar.projectName=java-app
                    '''
                }
            }
        }

        stage('Quality Gate') {
            steps {
                timeout(time: 10, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                }
            }
        }

        stage('OWASP Dependency Check') {
            steps {
                dependencyCheck additionalArguments: '''
                    --scan .
                    --format HTML
                    --format XML
                ''',
                odcInstallation: 'OWASP'
            }
        }

        stage('Publish OWASP Report') {
            steps {
                dependencyCheckPublisher pattern: '**/dependency-check-report.xml'
            }
        }

        stage('Package') {
            steps {
                sh 'mvn clean package -DskipTests'
            }
        }

        stage('Build Docker Image') {
            steps {
                sh """
                docker build \
                -t ${IMAGE_NAME}:${IMAGE_TAG} .
                """
            }
        }

        stage('Trivy Image Scan') {
            steps {
                sh """
                trivy image \
                --exit-code 1 \
                --severity HIGH,CRITICAL \
                ${IMAGE_NAME}:${IMAGE_TAG}
                """
            }
        }

        stage('Docker Login') {
            steps {
                withCredentials([usernamePassword(
                credentialsId: "${DOCKER_CREDENTIALS}",
                usernameVariable: 'USER',
                passwordVariable: 'PASS'
                )]) {

                    sh '''
                    echo $PASS | docker login -u $USER --password-stdin
                    '''
                }
            }
        }

        stage('Push Docker Image') {
            steps {
                sh '''
                docker push ${IMAGE_NAME}:${IMAGE_TAG}
                docker tag ${IMAGE_NAME}:${IMAGE_TAG} ${IMAGE_NAME}:latest
                docker push ${IMAGE_NAME}:latest
                '''
            }
        }

        stage('Update Kubernetes Manifest') {
            steps {

                dir('manifest') {

                    git branch: 'main',
                    credentialsId: "${GIT_CREDENTIALS}",
                    url: "${MANIFEST_REPO}"

                    sh """
                    sed -i 's#image:.*#image: ${IMAGE_NAME}:${IMAGE_TAG}#g' deployment.yaml
                    """

                    sh '''
                    git config user.email "jenkins@example.com"
                    git config user.name "Jenkins"

                    git add .

                    git commit -m "Updated image ${BUILD_NUMBER}" || true

                    git push origin main
                    '''
                }
            }
        }

        stage('Trigger ArgoCD Sync') {

            steps {

                sh '''

                argocd login argocd.example.com \
                --username admin \
                --password password \
                --insecure

                argocd app sync java-app

                argocd app wait java-app

                '''
            }
        }

    }

    post {

        success {
            echo "Deployment Successful"
        }

        failure {
            echo "Pipeline Failed"
        }

        always {
            cleanWs()
        }
    }
}
