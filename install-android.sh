#!/usr/bin/env bash
HD=$PWD

function abort_everything {
    echo "Aborting..."
    cd ${HD}
    exit 1
}

trap 'abort_everything' SIGINT

if [ ! -d android ]
then
    mkdir android
fi
cd android
if [ ! -d sdk ]
then
    mkdir sdk
fi
cd sdk
if [ ! -d android-sdk-linux ]
then
    echo "Downloading Android SDK files..."
    wget -q https://dl.google.com/android/android-sdk_r24.4.1-linux.tgz -O android-sdk.tgz
    if [ ! $? ]
    then abort_everything
    fi
    echo "Extracting Android SDK files..."
    tar -xvzf android-sdk.tgz --no-same-owner
    if [ ! $? ]
    then abort_everything
    fi
    rm android-sdk.tgz
else
    echo "Android SDK download not needed."
fi
export PATH=$PATH:$PWD/android-sdk-linux/tools
export PATH=$PATH:$PWD/android-sdk-linux/platform-tools
export ANDROID_HOME=$PWD/android-sdk-linux
echo "Updating Android SDK..."
echo y | android update sdk --no-ui --all --filter platform-tools | grep 'package installed'
echo y | android update sdk --no-ui --all --filter android-19 | grep 'package installed'
cd ..
if [ ! -d sdk-deployer ]
then
    git clone https://github.com/simpligility/maven-android-sdk-deployer.git sdk-deployer
else
    echo "sdk-deployer already present"
fi
cd sdk-deployer
mvn install -P 4.4 || true
cd ${HD}
