## Running the tests requires the following packages:

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

## The release script `./docker-datastax-release.sh` requires docker be installed:

* `sudo apt-get install docker.io` to install docker.
* `sudo usermod -aG docker $USER` to allow the current user to run docker.
* Then log out and log back in to get the permissions activated.
* Setup the username and password for the artifactory server in
  `~/.m2/settings.xml`.
* Then run `./docker-datastax-release.sh`.

## The next steps should each be run from a machine with the specific platform/architecture combinations below:

### Linux ARM64/aarch_64 Epoll libraries
* The following needs to be run from a Linux ARM64 platform (i.e. Graviton EC2 instance)
```
./mvnw clean && JAVA_HOME=$(/usr/libexec/java_home -v 1.8) ./mvnw -B -U -pl transport-native-unix-common,transport-native-epoll -Partifactory deploy -DskipTests -DaltDeploymentRepository="artifactory::default::https://repo.aws.dsinternal.org/artifactory/datastax-releases-local"
```

### MacOS AMD64/x86_64 KQueue libraries
* The follwoing needs to be run from an Intel based Mac:
```
./mvnw clean && JAVA_HOME=$(/usr/libexec/java_home -v 1.8) ./mvnw -B -U -pl resolver-dns-native-macos,transport-native-unix-common,transport-native-kqueue -Partifactory deploy -DskipTests -DaltDeploymentRepository="artifactory::default::https://repo.aws.dsinternal.org/artifactory/datastax-releases-local"
```
### MacOS ARM64/aarch_64 KQueue libraries
* The following needs to be run from an Apple Silicon based Mac (i.e. M1, M2, M3, etc)
```
./mvnw clean && JAVA_HOME=$(/usr/libexec/java_home -v 1.8) ./mvnw -B -U -Pmac-m1-cross-compile -pl resolver-dns-native-macos,transport-native-unix-common,transport-native-kqueue -Partifactory deploy -DskipTests -DaltDeploymentRepository="artifactory::default::https://repo.aws.dsinternal.org/artifactory/datastax-releases-local"
```

