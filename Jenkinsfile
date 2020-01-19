// 镜像仓库地址
def registry = "registry.us-east-1.aliyuncs.com"
// 命名空间
def namespace = "wzlinux"
// 镜像仓库项目
def project = "gitlab-pipeline"
// 镜像名称
def app_name = "citest"
// 镜像完整名称
def image_name = "${registry}/${namespace}/${project}:${app_name}-${BUILD_NUMBER}"
// git仓库地址
def git_address = "http://gitlab.wzlinux.com/root/gitlab-pipeline.git"
// fenzhi分支
def branch = "*/master"

// 认证
def aliyunhub_auth = "2187b285-f62e-437a-b6cf-d4a22c668891"
def gitlab_auth = "cca83969-0fe3-4aa8-9c37-172f19d7338f"

// K8s认证
def k8s_auth = "4fd99c44-2834-4aeb-9403-003ab579ad45"
// aliyun仓库secret_name
def aliyun_registry_secret = "aliyun-pull-secret"
// k8s部署后暴露的nodePort
def nodePort = "30666"


podTemplate(
    label: 'jenkins-agent', 
    cloud: 'kubernetes', 
    containers: [
       containerTemplate(name: 'jnlp', image: "wangzan18/jenkins-agent:maven-3.6.3"),
       containerTemplate(name: 'docker', image: 'docker:19.03.1-dind', ttyEnabled: true, command: 'cat')
    ],
    volumes: [
        hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock')
    ]){
    node('jenkins-agent'){
        stage('拉取代码') { // for display purposes
            checkout([$class: 'GitSCM',branches: [[name: '*/master']], userRemoteConfigs: [[credentialsId: "${gitlab_auth}", url: "${git_address}"]]])
        }
        stage('代码编译') {
        //    sh "mvn clean package -Dmaven.test.skip=true"
            sh "ls"
        }
        stage('构建镜像') {
            container('docker') {
                stage('打包镜像') {
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
        stage('部署到K8s'){
            sh """
                sed -i 's#\$IMAGE_NAME#${image_name}#' deployment.yaml
                sed -i 's#\$SECRET_NAME#${aliyun_registry_secret}#' deployment.yaml
                sed -i 's#\$NODE_PORT#${nodePort}#' deployment.yaml
            """
            kubernetesDeploy configs: 'deployment.yaml', kubeconfigId: "${k8s_auth}"
        }
        post {
            failure {
                 mail to: 'wangzan18@126.com',
                     subject: "Failed Pipeline: ${currentBuild.fullDisplayName}",
                     body: "Something is wrong with ${env.BUILD_URL}"
            }
        }
    }
}
