FROM jenkins/jenkins:lts-jdk17
USER root
RUN apt-get update && apt-get install -y \
    openssh-client \
    sshpass \
    && rm -rf /var/lib/apt/lists/*
USER jenkins
COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt
COPY init.groovy.d/ /usr/share/jenkins/ref/init.groovy.d/