#!/bin/bash

if [ ! -f /usr/bin/docker ]; then
    export DEBIAN_FRONTEND=noninteractive
    sudo apt-get update
    sudo apt-get install -y software-properties-common
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo apt-key add -
    sudo add-apt-repository \
        "deb [arch=amd64] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable"
    sudo apt-get update
    sudo apt-get install -y docker-ce
fi

docker build -f docker/Dockerfile-netty-centos6 . -t netty-centos6

docker run -it -v ~/.m2:/root/.m2 -v ~/.ssh:/root/.ssh -v ~/.gnupg:/root/.gnupg -v `pwd`:/code -w /code --entrypoint="" netty-centos6 bash -ic "mvn -B clean deploy -Partifactory -DskipTests -DaltDeploymentRepository=\"artifactory::default::https://repo.sjc.dsinternal.org/artifactory/datastax-releases-local\""

