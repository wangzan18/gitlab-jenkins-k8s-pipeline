# 一、概述
## 1.1、环境介绍
我们使用的是 AWS 的 EC2 来搭建我们的集群，安装方式使用 [kubeadm](https://kubernetes.io/docs/setup/production-environment/tools/kubeadm/install-kubeadm/) 来进行安装，如果使用二进制安装，可以参考我相关文档。
* 系统版本：ubuntu 16.04
* k8s 版本：1.17.1
* docker 版本：18.06-ce

## 1.2、流程图

![](https://s1.51cto.com/images/blog/202001/15/090e70d785d893b63f53bf25f90d791c.png?x-oss-process=image/watermark,size_16,text_QDUxQ1RP5Y2a5a6i,color_FFFFFF,t_100,g_se,x_10,y_10,shadow_90,type_ZmFuZ3poZW5naGVpdGk=)

## 1.3、集群配置


| 名称 | 配置 | 内网IP | 外网IP
| -------- | -------- | -------- | ---|
| k8s-master | 2核4GB| 172.31.20.184   | 54.226.118.74 |
| k8s-node1 | 2核4GB| 172.31.27.69  | 52.90.221.230 |
| k8s-node2 | 2核4GB| 172.31.30.9 | 3.85.219.119 |

![](https://s1.51cto.com/images/blog/202001/15/277385a467cb0369c63f0c038848a9a4.png?x-oss-process=image/watermark,size_16,text_QDUxQ1RP5Y2a5a6i,color_FFFFFF,t_100,g_se,x_10,y_10,shadow_90,type_ZmFuZ3poZW5naGVpdGk=)

# 二、k8s 部署
## 2.1、安装 docker

安装源大家可以参照官方文档 https://docs.docker.com/install/linux/docker-ce/ubuntu/ ，我这里不再进行演示，如没有特殊说明，操作将在三台集群上面都要执行。

```bash
apt-get install docker-ce=18.06.3~ce~3-0~ubuntu
systemctl enable docker
```

## 2.2、安装 kubeadm, kubelet and kubectl
安装源文档请参考官方文档 https://kubernetes.io/docs/setup/production-environment/tools/kubeadm/install-kubeadm ，详细步骤我这里进行省略。

```
sudo apt-get update && sudo apt-get install -y apt-transport-https curl
curl -s https://packages.cloud.google.com/apt/doc/apt-key.gpg | sudo apt-key add -
cat <<EOF | sudo tee /etc/apt/sources.list.d/kubernetes.list
deb https://apt.kubernetes.io/ kubernetes-xenial main
EOF
sudo apt-get update
sudo apt-get install -y kubelet kubeadm kubectl
sudo apt-mark hold kubelet kubeadm kubectl
```

## 2.3、安装 k8s 集群
请参考文档 https://kubernetes.io/docs/setup/production-environment/tools/kubeadm/create-cluster-kubeadm/ ，在 master 节点运行。
```
kubeadm init --kubernetes-version=v1.17.1 \
--pod-network-cidr=10.244.0.0/16 \
--service-cidr=10.96.0.0/12
```

在 node 节点上运行添加集群的命令。
```
kubeadm join 172.31.20.184:6443 --token w3fu9a.rs8eknt079n2e8r8 \
    --discovery-token-ca-cert-hash sha256:7392d6f6576b3c9ba5f78d1c54d9a0b1369f77bd498da8104a096b62c6b14c06
```

以后的 kubectl 都是在 master 节点上进行操作，添加 cni 插件，我们这里选择 flannel。
```
kubectl apply -f https://raw.githubusercontent.com/coreos/flannel/2140ac876ef134e0ed5af15c65e414cf26827915/Documentation/kube-flannel.yml
```

到目前为止，我们的集群已经创建完成，参照官方文档执行，过程很简单。
```
root@ip-172-31-20-184:~# kubectl get no
NAME               STATUS   ROLES    AGE   VERSION
ip-172-31-20-184   Ready    master   21m   v1.17.1
ip-172-31-27-69    Ready    <none>   16m   v1.17.1
ip-172-31-30-9     Ready    <none>   16m   v1.17.1
```

# 三、组件安装
整个 CI/CD 过程中我们用到了很多工具，比如 gitlab，jenkins，harbor，在生产环境，建议大家把 gitlab 和 harbor 放在独立的机器上面，我这里为了简便，直接放在 k8s 集群中。

## 3.1、安装 helm3
请参见官方文档 https://helm.sh/docs/intro/install/ 。
```
wget https://get.helm.sh/helm-v3.0.2-linux-amd64.tar.gz
tar xf helm-v3.0.2-linux-amd64.tar.gz
mv linux-amd64/helm /usr/local/bin/
chmod +x /usr/local/bin/helm
```

## 3.2、安装 gitlab
参见官方文档 https://docs.gitlab.com/charts/installation/ 。
```
helm repo add gitlab https://charts.gitlab.io/
```

后来发现 gitlab 目前还不支持 helm3，那我就选择了使用 EC2 来进行创建。
https://about.gitlab.com/install/#ubuntu?version=ce

访问地址：gitlab.wzlinux.com

## 3.3、安装 harbor

参见官方文档 https://github.com/goharbor/harbor-helm/blob/master/README.md

```
helm repo add harbor https://helm.goharbor.io
helm install my-release harbor/harbor
```

安装过程中还是出现了一些问题，是因为没有 pv，懒得去设置了，后来还是选择了单机进行安装，因为 docker 的一些要求，harbor 仓库配置了 https 证书。
参照下面文档安装 https://github.com/goharbor/harbor/blob/master/docs/installation_guide.md 。

访问地址：https://harbor.wzlinux.com

## 3.4、安装 jenkins
### 3.4.1、优点
我们以云原生的方式，将jenkins master，jenkins slave全部部署于kubernetes之上，从而打造一个高可用，弹性伸缩的CI/CD管道。

1. 推送代码到托管镜像仓库
1. gitlab 基于webhook触发jenkins pipeline项目
1. Jenkins master分配kubernetes slave作为项目的执行环境，同时k8s启动slave pod
1. Jenkins slave pod运行pipeline中指定的任务第一步从私有代码仓库拉下代码
1. Jenkins slave pod执行代码测试，测试完毕后依据代码仓库格式，构造镜像
1. Jenkins slave pod推送镜像到Harbor上
1. Jenkins slave pod执行应用服务的更新任务
1. 应用服务pod所在节点拉取相应的镜像，完成镜像的替换，即应用的更新

### 3.4.2、创建 nfs

因为 master 需要持久存储，我们这里就简单的选择 nfs ，我们在 master 节点上进行创建服务。
创建过程请参照文档 https://blog.csdn.net/qq_37860012/article/details/86717891 。
```
root@ip-172-31-20-184:/home/nfs# showmount -e 172.31.20.184
Export list for 172.31.20.184:
/home/nfs/jenkins *
```
master 节点目前授予权限。
```
chown ubuntu.ubuntu -R /home/nfs/jenkins
```

### 3.4.3、安装 jenkins
前面所需要的东西都已经配置好了，那我们开始安装 jenkins，请应用我下面的 yaml 文件
```
kubectl apply -f https://raw.githubusercontent.com/wangzan18/jenkins-cicd/master/master/jenkins.yaml
```
关于动态创建 jenkins agent，具体使用方法请参照我的博文 https://blog.51cto.com/wzlinux/2467307 。

访问地址：http://jenkins.wzlinux.com:30814/

### 3.4.4、gitlab 触发 jenkins
首先为 jenkins 安装 gitlab 插件。

![](https://s1.51cto.com/images/blog/202001/16/555ded9891e03a8056225a16d3ad988a.png?x-oss-process=image/watermark,size_16,text_QDUxQ1RP5Y2a5a6i,color_FFFFFF,t_100,g_se,x_10,y_10,shadow_90,type_ZmFuZ3poZW5naGVpdGk=)

创建一个 jenkins pipeline job，命名为 `gitlab-pipeline`，选中 gitlab 触发器。

![](https://s1.51cto.com/images/blog/202001/16/0f30c38d2842c27471e8c77a0a5c2136.png?x-oss-process=image/watermark,size_16,text_QDUxQ1RP5Y2a5a6i,color_FFFFFF,t_100,g_se,x_10,y_10,shadow_90,type_ZmFuZ3poZW5naGVpdGk=)

记录生成的数值，然后回到我们的 gitlab project 里面，填写好 jenkins 的信息。

![](https://s1.51cto.com/images/blog/202001/16/68dea43625a5ed0186339771f7fb17ed.png?x-oss-process=image/watermark,size_16,text_QDUxQ1RP5Y2a5a6i,color_FFFFFF,t_100,g_se,x_10,y_10,shadow_90,type_ZmFuZ3poZW5naGVpdGk=)

然后我们提交代码到仓库，可以看到 jenkins 被触发了。

![](https://s1.51cto.com/images/blog/202001/16/591eed3ced5c4ec00bab4f7c7917be91.png?x-oss-process=image/watermark,size_16,text_QDUxQ1RP5Y2a5a6i,color_FFFFFF,t_100,g_se,x_10,y_10,shadow_90,type_ZmFuZ3poZW5naGVpdGk=)

# 四、自动化构建

因为 jenkins 在构建的时候，会向 gitlab 拉取代码，因为我们的 gitlab 是私有仓库，所以需要为 jenkins 配置权限，同样的，harbor 仓库也是私有的，jenkins 在向 harbor 推镜像的时候，也是需要相应的权限。

## 4.1、为 jenkins 配置 gitlab 凭证
切换到 jenkins 凭据界面，创建我们需要的凭据，填写你 gitlab 的账户密码即可，你也可以单独为这个仓库创建一个用户。

![](https://s1.51cto.com/images/blog/202001/17/213f88391b66f5dd78651a3b97f098f1.png?x-oss-process=image/watermark,size_16,text_QDUxQ1RP5Y2a5a6i,color_FFFFFF,t_100,g_se,x_10,y_10,shadow_90,type_ZmFuZ3poZW5naGVpdGk=)

## 4.2、为 jenkins 配置 harbor 凭证

首先为 jenkins 在 harbor 中创建一个用户 `jenkins`，然后登陆用户创建一个项目`gitlab-pipeline`，这里我不再掩饰，然后同意的方式，在 jenkins 为其创建凭据。

![](https://s1.51cto.com/images/blog/202001/17/8f203055fa043d26e88a5febce8e6640.png?x-oss-process=image/watermark,size_16,text_QDUxQ1RP5Y2a5a6i,color_FFFFFF,t_100,g_se,x_10,y_10,shadow_90,type_ZmFuZ3poZW5naGVpdGk=)

最终生成的两个凭据如下，记录下各凭据的 ID，我们后面会引用到。

![](https://s1.51cto.com/images/blog/202001/17/18c896bb148846856c5f66c64b1ffe1a.png?x-oss-process=image/watermark,size_16,text_QDUxQ1RP5Y2a5a6i,color_FFFFFF,t_100,g_se,x_10,y_10,shadow_90,type_ZmFuZ3poZW5naGVpdGk=)

## 4.3、为 job 添加 pipeline 脚本
我们前面测试 gitlab 触发 jenkins 的时候已经创建了一个 job，我们使用 SCM 的方式，我已经把我测试的仓库同步到 github了，大家查看代码可以去我的 github 地址：https://github.com/wangzan18/gitlab-pipeline.git

![](https://s1.51cto.com/images/blog/202001/17/d7b4bdcec6b348ed6a21a959f2a66340.png?x-oss-process=image/watermark,size_16,text_QDUxQ1RP5Y2a5a6i,color_FFFFFF,t_100,g_se,x_10,y_10,shadow_90,type_ZmFuZ3poZW5naGVpdGk=)

出现一些错误，可能是 harbor 仓库的问题，我单独去 push 镜像也是有问题的，目前这个问题我还不知道如何去解决。

![](https://s1.51cto.com/images/blog/202001/17/05e86c54972e7859b48bee963bdf2307.png?x-oss-process=image/watermark,size_16,text_QDUxQ1RP5Y2a5a6i,color_FFFFFF,t_100,g_se,x_10,y_10,shadow_90,type_ZmFuZ3poZW5naGVpdGk=)

那我们就直接测试上传到阿里云的 docker hub 吧，首先我们去创建一个仓库。

![](https://s1.51cto.com/images/blog/202001/17/7117ad81ade63c6b7cd59f31c528ea7f.png?x-oss-process=image/watermark,size_16,text_QDUxQ1RP5Y2a5a6i,color_FFFFFF,t_100,g_se,x_10,y_10,shadow_90,type_ZmFuZ3poZW5naGVpdGk=)

相关的凭据也需要在 jenkins 里面配置好，我这里不再掩饰，同样的，job 我们也选择 SCM 获取，脚本路径选择 Jenkinsfile，然后我们选择去执行，这时候就没有错误了。

![](https://s1.51cto.com/images/blog/202001/17/a2b523539ee40fc7646ef3eb820e1abf.png?x-oss-process=image/watermark,size_16,text_QDUxQ1RP5Y2a5a6i,color_FFFFFF,t_100,g_se,x_10,y_10,shadow_90,type_ZmFuZ3poZW5naGVpdGk=)

在 Jenkinsfile 中，我们用到的两个容器，一个是 jenkins agent，用来连接 jenkins server，还有一个 docker，用来 build 镜像，如果你有其他的需求，可以去找相应的镜像，在特定的 stage 的时候去应用。

然后我们去阿里云的镜像仓库查看一下我们构建好的镜像。

![](https://s1.51cto.com/images/blog/202001/17/350329a55a3038204ffc5e2c09690ca1.png?x-oss-process=image/watermark,size_16,text_QDUxQ1RP5Y2a5a6i,color_FFFFFF,t_100,g_se,x_10,y_10,shadow_90,type_ZmFuZ3poZW5naGVpdGk=)

# 五、自动化部署
既然我们已经把镜像打包构建好，那我们需要把它部署到 k8s 集群中。

## 5.1、给 jenkins 配置 k8s 认证
我们需要告诉 jenkins ，我们的 k8s 集群在哪里，怎么去请求 api 创建 pod。因为我们为 serviceaccount jenkins 已经配置了 RBAC，所以我们可以为其创建一个 kubeconfig，这里我为了简单，使用集群管理员的 config，也就是`~.kube/config`。

同样的，我们需要给 jenkins 安装插件 `Kubernetes Continuous Deploy`，然后创建对应的凭据。

![](https://s1.51cto.com/images/blog/202001/17/ef415b1c7614695c9dec0e5378f9dd68.png?x-oss-process=image/watermark,size_16,text_QDUxQ1RP5Y2a5a6i,color_FFFFFF,t_100,g_se,x_10,y_10,shadow_90,type_ZmFuZ3poZW5naGVpdGk=)

记录下凭据的 ID，稍后我们会在 pipeline 中使用。

## 5.2、k8s 添加 aliyun 仓库 secret
在可以执行 kubectl 命令的服务器上面执行。
```
kubectl create secret docker-registry aliyun-pull-secret --docker-username=wangzan18@126.com \
--docker-password=YOUR_PASSWORD \
--docker-email=wangzan18@126.com \
--docker-server=registry.us-east-1.aliyuncs.com
```

## 5.3、更新 pipeline 脚本。
大家可以查看我 github 仓库 jenkins 的变化情况。

已经创建我们要部署的 deployment.yaml 文件，都可以在 github 上面看到 https://github.com/wangzan18/gitlab-pipeline.git 。

## 5.4、代码提交触发更新
我们可以看到 jenkins pipeline 都已经更新成功。

![](https://s1.51cto.com/images/blog/202001/17/6e82f9dd122d8e7415bd127dcf687e05.png?x-oss-process=image/watermark,size_16,text_QDUxQ1RP5Y2a5a6i,color_FFFFFF,t_100,g_se,x_10,y_10,shadow_90,type_ZmFuZ3poZW5naGVpdGk=)

然后查看我的页面是否正常。

![](https://s1.51cto.com/images/blog/202001/17/46c9f161025f2d0adc221dee61bb85f7.png?x-oss-process=image/watermark,size_16,text_QDUxQ1RP5Y2a5a6i,color_FFFFFF,t_100,g_se,x_10,y_10,shadow_90,type_ZmFuZ3poZW5naGVpdGk=)

那我们把代码更新一下，然后我们可以看下 k8s 集群 pod 的变化情况。
```
wangzan:~ $ kubectl get pod -w
NAME                       READY   STATUS    RESTARTS   AGE
jenkins-5df4dff655-f4gk8   1/1     Running   0          26h
myapp1                     1/1     Running   0          47h
web-5bd9f98844-622hn       1/1     Running   0          3m35s
web-5bd9f98844-dppvh       1/1     Running   0          4m30s
web-5bd9f98844-wp7g7       1/1     Running   0          3m58s
jenkins-agent-004xl-vdjjl   0/2     Pending   0          0s
jenkins-agent-004xl-vdjjl   0/2     Pending   0          0s
jenkins-agent-004xl-vdjjl   0/2     ContainerCreating   0          0s
jenkins-agent-004xl-vdjjl   2/2     Running             0          1s
web-559864cbd7-fj66t        0/1     Pending             0          0s
web-559864cbd7-fj66t        0/1     Pending             0          0s
web-559864cbd7-fj66t        0/1     ContainerCreating   0          0s
jenkins-agent-004xl-vdjjl   2/2     Terminating         0          30s
web-559864cbd7-fj66t        0/1     Running             0          3s
web-559864cbd7-fj66t        1/1     Running             0          30s
web-5bd9f98844-622hn        1/1     Terminating         0          4m45s
web-559864cbd7-jssmn        0/1     Pending             0          0s
web-559864cbd7-jssmn        0/1     Pending             0          0s
web-559864cbd7-jssmn        0/1     ContainerCreating   0          0s
web-5bd9f98844-622hn        0/1     Terminating         0          4m46s
jenkins-agent-004xl-vdjjl   0/2     Terminating         0          61s
jenkins-agent-004xl-vdjjl   0/2     Terminating         0          61s
web-559864cbd7-jssmn        0/1     Running             0          2s
jenkins-agent-004xl-vdjjl   0/2     Terminating         0          62s
web-5bd9f98844-622hn        0/1     Terminating         0          4m50s
web-5bd9f98844-622hn        0/1     Terminating         0          4m50s
jenkins-agent-004xl-vdjjl   0/2     Terminating         0          74s
jenkins-agent-004xl-vdjjl   0/2     Terminating         0          74s
web-559864cbd7-jssmn        1/1     Running             0          24s
web-5bd9f98844-wp7g7        1/1     Terminating         0          5m32s
web-559864cbd7-2jl6l        0/1     Pending             0          0s
web-559864cbd7-2jl6l        0/1     Pending             0          0s
web-559864cbd7-2jl6l        0/1     ContainerCreating   0          0s
```

然后再去查看我的页面。

![](https://s1.51cto.com/images/blog/202001/17/c021da5ba84a033dc391f87b97732c3d.png?x-oss-process=image/watermark,size_16,text_QDUxQ1RP5Y2a5a6i,color_FFFFFF,t_100,g_se,x_10,y_10,shadow_90,type_ZmFuZ3poZW5naGVpdGk=)

也去我们的仓库看下各个版本的镜像。

![](https://s1.51cto.com/images/blog/202001/17/c16ed7cece4573471763205b7e4bcafa.png?x-oss-process=image/watermark,size_16,text_QDUxQ1RP5Y2a5a6i,color_FFFFFF,t_100,g_se,x_10,y_10,shadow_90,type_ZmFuZ3poZW5naGVpdGk=)

基本上整个 CI/CD 的过程已经比较完善。

## 5.5、pipeline 脚本
最终的 pipeline 脚本如下，对其中语法不理解的，可以使用官网的流水线语法编辑器。
```
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
    }
}
```

# 六、报警通知
## 6.1、邮件报警
```
post {
    failure {
        mail to: 'team@example.com',
             subject: "Failed Pipeline: ${currentBuild.fullDisplayName}",
             body: "Something is wrong with ${env.BUILD_URL}"
    }
}
```

## 6.2、微信报警

# 七、与 github 集成
## 7.1、安装插件
安装插件`GitHub Integration Plugin`。

## 7.2、添加触发器
在 pipeline 的 job里面，我们勾选下面选项：

![](https://s1.51cto.com/images/blog/202001/19/c4acc0a8d2aa724f5d5179771722e463.png?x-oss-process=image/watermark,size_16,text_QDUxQ1RP5Y2a5a6i,color_FFFFFF,t_100,g_se,x_10,y_10,shadow_90,type_ZmFuZ3poZW5naGVpdGk=)

## 7.3、github 配置 webhook

![](https://s1.51cto.com/images/blog/202001/19/546a048ff6c9bc43347ca3700033450d.png?x-oss-process=image/watermark,size_16,text_QDUxQ1RP5Y2a5a6i,color_FFFFFF,t_100,g_se,x_10,y_10,shadow_90,type_ZmFuZ3poZW5naGVpdGk=)

## 7.4、多分支流水线创建过程
![](https://s1.51cto.com/images/blog/202001/19/ea98b9f0c24eefe73d5c4a2ea2aa0056.png?x-oss-process=image/watermark,size_16,text_QDUxQ1RP5Y2a5a6i,color_FFFFFF,t_100,g_se,x_10,y_10,shadow_90,type_ZmFuZ3poZW5naGVpdGk=)

### 配置分支源
这里主要也是去监控 github 的提交情况，发现有提交就去构建，相关凭证可以去申请 github access token。

![](https://s1.51cto.com/images/blog/202001/19/05905cdedde8aa1530446ccb54038b1c.png?x-oss-process=image/watermark,size_16,text_QDUxQ1RP5Y2a5a6i,color_FFFFFF,t_100,g_se,x_10,y_10,shadow_90,type_ZmFuZ3poZW5naGVpdGk=)

### 配置 Jenkinsfile
![](https://s1.51cto.com/images/blog/202001/19/14e36353d8a999a19c803c039bbc61ff.png?x-oss-process=image/watermark,size_16,text_QDUxQ1RP5Y2a5a6i,color_FFFFFF,t_100,g_se,x_10,y_10,shadow_90,type_ZmFuZ3poZW5naGVpdGk=)


参考文档：https://github.com/wangzan18/gitlab-pipeline.git
https://github.com/jenkinsci/kubernetes-plugin
https://blog.51cto.com/wzlinux/2467307



