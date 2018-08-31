#!/usr/bin/env bash

set -o xtrace
set -o errexit
set -o nounset
set -o pipefail

if [ ! -d ./build ]
then
  sudo mkdir -m 0000 ./build
fi

if ! sudo mountpoint -q ./build
then
  sudo mount -t tmpfs none ./build
fi

cd ./build

VERSION_JETTY=9.4.11.v20180605

if [ ! -f ./jetty-distribution-"$VERSION_JETTY".tar.gz ]
then
  wget -O./jetty-distribution-"$VERSION_JETTY".tar.gz https://repo1.maven.org/maven2/org/eclipse/jetty/jetty-distribution/"$VERSION_JETTY"/jetty-distribution-"$VERSION_JETTY".tar.gz
fi

if [ ! -d ./jetty-distribution-"$VERSION_JETTY" ]
then
  tar -xmf ./jetty-distribution-"$VERSION_JETTY".tar.gz
fi

VERSION_BITCOINJ=0.14.7

if [ ! -f ./bitcoinj-core-"$VERSION_BITCOINJ"-bundled.jar ]
then
  wget -O./bitcoinj-core-"$VERSION_BITCOINJ"-bundled.jar https://search.maven.org/remotecontent'?'filepath'='org/bitcoinj/bitcoinj-core/"$VERSION_BITCOINJ"/bitcoinj-core-"$VERSION_BITCOINJ"-bundled.jar
fi

VERSION_SLF4J=1.7.25

if [ ! -f ./slf4j-"$VERSION_SLF4J".tar.gz ]
then
  wget -O./slf4j-"$VERSION_SLF4J".tar.gz https://www.slf4j.org/dist/slf4j-1.7.25.tar.gz
fi

if [ ! -f ./slf4j-"$VERSION_SLF4J"/slf4j-simple-"$VERSION_SLF4J".jar ]
then
  tar -xf ./slf4j-"$VERSION_SLF4J".tar.gz slf4j-"$VERSION_SLF4J"/slf4j-simple-"$VERSION_SLF4J".jar
fi

mkdir -p ./classpath

JETTY_JARS="$(find ./jetty-distribution-"$VERSION_JETTY" | egrep '\.jar$' | tr '\n' ':' | sed -e 's/:$//')"

ALL_JARS="$JETTY_JARS":bitcoinj-core-"$VERSION_BITCOINJ"-bundled.jar:slf4j-"$VERSION_SLF4J"/slf4j-simple-"$VERSION_SLF4J".jar

if [ "${1-}" == "run" ]
then
  cp ./../ChallengeServer.java .
  javac -Xlint:deprecation -d ./classpath -cp "$ALL_JARS" ChallengeServer.java
  # sudo required for binding to port 80. here we just do the easy way
  # but in production, some more advanced mechanisms might be used to
  # avoid running the JVM as root
  sudo java -cp ./classpath:"$ALL_JARS" challenge.ChallengeServer
fi

if [ "${1-}" == "runsa" ]
then
  cp ./../ChallengeStandalone.java .
  javac -Xlint:deprecation -d ./classpath -cp "$ALL_JARS" ChallengeStandalone.java
  java -cp ./classpath:"$ALL_JARS" challenge.ChallengeStandalone
fi
