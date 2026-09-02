#!/bin/bash

if ! which docker > /dev/null ; then
    export DEBIAN_FRONTEND=noninteractive
    sudo apt-get update
    sudo apt-get install -y software-properties-common
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo apt-key add -
    sudo add-apt-repository \
        "deb [arch=amd64] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable"
    sudo apt-get update
    sudo apt-get install -y docker-ce
fi

sudo docker build -f docker/Dockerfile-netty-centos6 -t netty-centos6 .
sudo docker run -t --network host -v ~/.m2:/root/.m2:Z -v ~/.ssh:/root/.ssh:Z -v ~/.gnupg:/root/.gnupg:Z -v `pwd`:/code:Z -w /code --entrypoint="" netty-centos6 bash -ic "./mvnw -B clean deploy -Partifactory -DskipTests -DaltDeploymentRepository=\"artifactory::default::https://maven.pkg.github.com/riptano/netty\""
