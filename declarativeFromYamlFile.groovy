pipeline {
  agent {
    kubernetes {
      yamlFile 'declarativeYamlFile.yml'
    }
  }
  environment {
     registry = "registry.us-east-1.aliyuncs.com"
     namespace = "wzlinux"
     project = "gitlab-pipeline"
     app_name = "citest"
     image_name = "${registry}/${namespace}/${project}:${app_name}-${BUILD_NUMBER}"
     git_address = "https://github.com/wangzan18/gitlab-pipeline.git"
     branch = "*/master"
     aliyunhub_auth = "2187b285-f62e-437a-b6cf-d4a22c668891"
     github_auth = "fd47f521-5314-4c74-838c-1440b9e32ceb"
     k8s_auth = "4fd99c44-2834-4aeb-9403-003ab579ad45"
     aliyun_registry_secret = "aliyun-pull-secret"
     nodePort = "30666"
  }
  stages {
    stage('拉取代码') {
      steps {
        checkout([$class: 'GitSCM',branches: [[name: '*/master']], userRemoteConfigs: [[credentialsId: "${github_auth}", url: "${git_address}"]]])
      }
    }
    stage('代码编译') {
      steps {
        sh 'ls'
      }
    }
    stage('打包镜像') {
       steps {
          container('docker') {
             withCredentials([usernamePassword(credentialsId: "${aliyunhub_auth}", passwordVariable: 'password', usernameVariable: 'username')]) {
               sh """
                  docker build -t ${image_name} .
                  docker login -u ${username} -p '${password}' ${registry}
                  docker push ${image_name}
               """
             }
          }
       }
    }
    stage('代码部署') {
      steps {
        sh """
            sed -i 's#\$IMAGE_NAME#${image_name}#' deployment.yaml
            sed -i 's#\$SECRET_NAME#${aliyun_registry_secret}#' deployment.yaml
            sed -i 's#\$NODE_PORT#${nodePort}#' deployment.yaml
        """
        kubernetesDeploy configs: 'deployment.yaml', kubeconfigId: "${k8s_auth}"
      }
    }
  }
  post {
    success {
        mail to: 'wangzan18@126.com',
             subject: "Successed Pipeline: ${currentBuild.fullDisplayName}",
             body: "Everythins is right with ${env.BUILD_URL}"
    }
    failure {
        mail to: 'wangzan18@126.com',
             subject: "Failed Pipeline: ${currentBuild.fullDisplayName}",
             body: "Something is wrong with ${env.BUILD_URL}"
    }
  }
}
