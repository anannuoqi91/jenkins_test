FROM jenkins/jenkins:lts-jdk17

# 复制插件清单并安装（构建镜像时完成）
COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

# 复制 JCasC 配置（也可以只挂载，不 COPY）
COPY casc /usr/share/jenkins/ref/casc
