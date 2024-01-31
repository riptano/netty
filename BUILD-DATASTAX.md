Running the tests requires the following packages:

```
sudo apt-get install autoconf automake libtool make tar gcc \
                     libaio1 libaio-dev \
                     libapr1
```

* `autoconf`, `automake`, `libtool`, `make`, `tar`, and `gcc` are the
  requirements listed by the build instructions for the netty project.
* `libaio1` and `libaio-dev` are required for the AIO extensions added
  in this fork.
* `libapr1` ("Apache Portable Runtime Library") is a dependency of `tcnative`,
  a dependency of netty. It's not needed for building the project, but it is
  required for running the tests.

The release script `./docker-datastax-release.sh` requires docker be installed:

* `sudo apt-get install docker.io` to install docker.
* `sudo usermod -aG docker $USER` to allow the current user to run docker.
* Then log out and log back in to get the permissions activated.
* Setup the username and password for the artifactory server in
  `~/.m2/settings.xml`.
* Then run `./docker-datastax-release.sh`.
* Follow this by releasing the MacOS specific kqueue library (must be run on MacOS):
  `./mvnw clean && JAVA_HOME=$(/usr/libexec/java_home -v 1.8) ./mvnw -B -U -pl transport-native-unix-common,transport-native-kqueue -Partifactory deploy -DskipTests -DaltDeploymentRepository="artifactory::default::https://repo.aws.dsinternal.org/artifactory/datastax-releases-local"`


---

To build and publish aarch64 variant:
- requires `docker` and `docker-compose`
- setup artifactory server credentials in `~/.m2/settings.xml`
- run `docker-compose -f docker/docker-compose.centos-7.dse-aarch.yaml run cross-compile-aarch64-build-and-publish`

Note the centos7.6 base image for `Dockerfile.cross_compile_aarch64` only exists for `linux/amd64`. The above `docker-compose` command will only work from a `linux/amd64` environment.
