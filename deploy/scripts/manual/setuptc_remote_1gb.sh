#!/bin/bash
if [[ -z "$2" ]]; then
  echo "Usage: setup_remote_1gb.sh [nServers] [latency_file]"
  exit
fi

RED='\033[0;31m'
BLUE='\033[0;34m'
GREEN='\033[0;32m'
NC='\033[0m' # No Color

n_Servers=$1
n_Nodes=$((n_Servers))
latency_file=$2

all_nodes=$(./nodes.sh)

declare -a nodes

idx=0
for i in $all_nodes; do
  if [ $idx -eq "$n_Nodes" ]; then
    break
  fi
  nodes+=($i)
  idx=$((idx + 1))
done

if [ $idx -lt "$n_Nodes" ]; then
  echo -e "${RED}Not enough nodes: Got $idx, required ${n_Nodes}$NC"
  exit
fi

echo -e "${GREEN}Nodes: $NC${nodes[*]}"

echo Setting up TC...

print_and_exec() {
  node=$1
  cmd=$2
  echo -e "$GREEN -- $node -- $NC$cmd"
  ssh  "$node" "$cmd" 2>&1 | sed "s/^/[$node] /"
}

i=0
# shellcheck disable=SC2095
while read -r raw_line; do
  if [ $i -ge "$n_Nodes" ]; then
    echo "exit-----------"
    exit
  fi
  echo -e "${RED} ${nodes[$i]}----------------------------------------------------------------------${NC}"
  echo -e "$RED Line: $NC$raw_line "
  if [[ $raw_line == \#* ]]; then  # 跳过注释
    echo "Ignored-----------"
    continue
  fi
  j=0
  IFS=', ' read -r -a line <<<"$raw_line"
  # 1000  1000       -1   46   63   102  93
  download=${line[0]}
  upload=${line[1]}

  echo -e "${BLUE}UPLOAD $upload$NC"
  #print_and_exec "${nodes[$i]}" "sudo tc qdisc del dev eth0 root && sudo tc qdisc add dev eth0 root handle 1: htb default 1"
  print_and_exec "${nodes[$i]}" "sudo tc qdisc add dev eth0 root handle 1: htb default 1"
  cmd="sudo tc class add dev eth0 parent 1: classid 1:1 htb rate ${upload}mbit"
  print_and_exec "${nodes[$i]}" "$cmd"
  # 剩余参数从2 到结尾的所有参数 类似：  -1  46  63   102  
  for n in "${line[@]:2}"; do
    if [ $i -ne $j ]; then
      if [ $j -ge "$n_Nodes" ]; then
        echo "j大于指定数量"
        continue
      fi
      target_ip=$(getent hosts "${nodes[$j]}" | awk '{print $1}')
      echo -e "latency from ${GREEN}${nodes[$i]}${NC} to ${BLUE}${nodes[$j]}${NC} ($target_ip) is ${RED}${n}${NC}"
      cmd1="sudo tc class add dev eth0 parent 1:1 classid 1:1$j htb rate 200mbit ceil 20000mbit && "
      cmd2="sudo tc qdisc add dev eth0 parent 1:1$j netem delay ${n}ms $((n / 10))ms distribution normal && "
      cmd3="sudo tc filter add dev eth0 protocol ip parent 1:0 prio 1 u32 match ip dst ${nodes[$j]} flowid 1:1$j"
#      ssh  "${nodes[$i]}" "$cmd1$cmd2$cmd3" 2>&1 | sed "s/^/[${nodes[$i]}] /"
      print_and_exec "${nodes[$i]}" "$cmd1$cmd2$cmd3"
    else
      echo "没执行"
    fi
    j=$((j + 1))
  done
  i=$((i + 1))
done  <"$latency_file"
