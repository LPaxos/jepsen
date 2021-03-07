[Jepsen](https://github.com/jepsen-io/jepsen) tests for [LattiStore](https://github.com/LPaxos/lattistore).

## Prerequisites

Clone the repository:
```
git clone https://github.com/LPaxos/jepsen.git
cd jepsen
```
Pull the submodule (there is one with a protobuf file):
```
git submodule init
git submodule update
```

You must be able to compile the gRPC service definitions from the `resources/proto/client/client.proto` file. For that, the following must be present under your `$PATH`:
- `protoc` - the [Protobuf Compiler](https://github.com/protocolbuffers/protobuf). Should be available in your Linux distribution's repositories. For example, on Ubuntu 20: `sudo apt install protobuf-compiler`. You can also download the binary from the official protobuf [releases page](https://github.com/protocolbuffers/protobuf/releases).
- `protoc-gen-grpc-java`: gRPC Java code generation plugin for the Protobuf Compiler. You can get it from the [Maven Repository](https://mvnrepository.com/artifact/io.grpc/protoc-gen-grpc-java), e.g. the [1.33.1 version](https://repo1.maven.org/maven2/io/grpc/protoc-gen-grpc-java/1.33.1/). The [official repository](https://github.com/grpc/grpc-java/tree/master/compiler) describes how to build it.

After ensuring that the `protoc` and `protoc-gen-grpc-java` commands are available, run
```
make
```
to compile the definitions.
Tested with protoc 3.6.1 and protoc-gen-java 1.33.1.

## Running

This is a [Clojure](https://clojure.org/) project (with some auxiliary Java). Use [Leiningen](https://leiningen.org/) to run it.
Example usage:
```
lein run test \
    --node 10.82.33.253 --node 10.82.33.88 --node 10.82.33.246 --node 10.82.33.202 --node 10.82.33.182 \
    --time-limit 60 \
    --concurrency 30 --rate 30 \
    --timeout 3000 \
    --workload append \
    --nemesis partition \
    --test-count 1
```

The framework will start a cluster with one node for each provided IP; for the above example it will start a cluster of 5 nodes. The following requirements are assumed by the test (otherwise it may fail to start):
- `ssh root@IP` works without a password for each provided IP
- each of these machines runs an Ubuntu 20 instance
- on each machine, the `/root` directory contains LattiStore's `server` binary: `/root/server`. The binaries must be the same. The binary can be obtained from the `target` directory after compiling LattiStore using Cargo.

## Setting up test machines with Linux Containers

As seen above, running the test requires having test machines available with root access.

You can use [Linux Containers](https://linuxcontainers.org/) as the test machines. I recommend the [LXD manager](https://linuxcontainers.org/lxd/introduction/) due to its simplicity of use. See the [LXD getting started](https://linuxcontainers.org/lxd/getting-started-cli/) page for configuration instructions.

After configuring LXD, launch the desired number of containers with Ubuntu 20.04:
```
lxc launch ubuntu:20.04 node1
lxc launch ubuntu:20.04 node2
...
```
the instances should be started automatically.

You need to setup `ssh` access for each instance and update its package manager:
- connect to the instance, enable ssh root login, restart the `sshd` service and update the package manager.
You'll also need to retrieve the container's IP address. For example:
```
$ lxc exec node1 -- /bin/bash
root@node1:~# echo 'PermitRootLogin yes' >> /etc/ssh/sshd_config
root@node1:~# service sshd restart
root@node1:~# ip a | grep inet.*eth0
    inet 10.82.33.243/24 brd 10.82.33.255 scope global dynamic eth0
root@node1:~# apt update
Hit:1 http://security.ubuntu.com/ubuntu focal-security InRelease         
...
Reading state information... Done
1 package can be upgraded. Run 'apt list --upgradable' to see it.
root@node1:~# exit
$
```
LattiStore does not support IPv6 so you need to ensure that the containers have IPv4 addresses. I recall having a problem on Fedora 32 with the containers not getting an IPv4 address (only IPv6); I found the solution [here](https://github.com/lxc/lxd/issues/7294).
- Append one of your public ssh keys to the `authorized_keys` file in the container, e.g.:
```
cat ~/.ssh/id_rsa.pub | lxc exec node1 -- sh -c "cat >> /root/.ssh/authorized_keys"
```
The key must be passwordless. If you don't have a passwordless ssh key, [generate one and add it to the SSH agent](https://docs.github.com/en/free-pro-team@latest/github/authenticating-to-github/generating-a-new-ssh-key-and-adding-it-to-the-ssh-agent).
- Check that you can connect to the instance:
```
$ ssh root@10.82.33.243
Welcome to Ubuntu 20.04.1 LTS (GNU/Linux 5.4.0-48-generic x86_64)
...
```

repeat the above procedure for every instance.

Finally, you need to place the `server` binary in the `/root` directory. You can use the `lxc file push` command, e.g.:
```
$ lxc file push <path-to-server> node1/root/
```
