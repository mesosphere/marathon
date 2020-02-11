mkdir target
rsync -avz ../../ target/marathon/ --exclude docker --exclude target --delete
sed -i.bak '/mem/d' target/marathon/.sbtopts

docker build . --build-arg MESOS_PKG_VERSION=1.9.0-2.0.1
