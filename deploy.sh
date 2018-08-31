#!/usr/bin/env bash

function ssh_()
{
  ssh -i ./local/kp ubuntu@"$(cat ./local/ip)" "$@"
}

function f()
{
  mkdir -p /tmp/challenge
  
  cd /tmp/challenge
  
  tar -xf /tmp/archive.tar.gz
  
  ./mk.sh run
}

tar -zc ./mk.sh ./ChallengeServer.java | ssh_ 'cat >/tmp/archive.tar.gz'

( declare -f f ; echo f ) | ssh_ 'cat >/tmp/script && bash /tmp/script'

