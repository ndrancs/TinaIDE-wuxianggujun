#!/bin/sh
# ReTerminal - 容器内初始化脚本
# 设置环境并进入交互 shell

set -e  # Exit immediately on Failure

export PATH=/bin:/sbin:/usr/bin:/usr/sbin:/usr/share/bin:/usr/share/sbin:/usr/local/bin:/usr/local/sbin:/system/bin:/system/xbin
export HOME=/root

# DNS（若缺失）
if [ ! -s /etc/resolv.conf ]; then
    echo "nameserver 8.8.8.8" > /etc/resolv.conf
fi

# 提示符 & 基础包
export PS1="\[\e[38;5;46m\]\u\[\033[39m\]@tinaide \[\033[39m\]\w \[\033[0m\]\\$ "
export PIP_BREAK_SYSTEM_PACKAGES=1

required_packages="bash gcompat glib nano"
missing_packages=""
for pkg in $required_packages; do
    if ! apk info -e $pkg >/dev/null 2>&1; then
        missing_packages="$missing_packages $pkg"
    fi
done

if [ -n "$missing_packages" ]; then
    echo -e "\e[34;1m[*] \e[0mInstalling Important packages\e[0m"
    apk update && apk upgrade
    apk add $missing_packages
    if [ $? -eq 0 ]; then
        echo -e "\e[32;1m[+] \e[0mSuccessfully Installed\e[0m"
    fi
    echo -e "\e[34m[*] \e[0mUse \e[32mapk\e[0m to install new packages\e[0m"
fi

# 修复 linker 警告（ash 兼容：使用 [ ] 而非 [[ ]]）
if [ ! -f /linkerconfig/ld.config.txt ]; then
    mkdir -p /linkerconfig
    touch /linkerconfig/ld.config.txt
fi

# 进入交互或执行命令
if [ "$#" -eq 0 ]; then
    . /etc/profile
    export PS1="\[\e[38;5;46m\]\u\[\033[39m\]@tinaide \[\033[39m\]\w \[\033[0m\]\\$ "
    cd $HOME
    /bin/ash
else
    exec "$@"
fi
