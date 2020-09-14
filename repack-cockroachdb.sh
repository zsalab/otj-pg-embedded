#!/bin/bash -ex
# NB: This is the *server* version, which is not to be confused with the client library version.
# The important compatibility point is the *protocol* version, which hasn't changed in ages.
VERSION=20.1.5

RSRC_DIR=$PWD/target/generated-resources

[ -e $RSRC_DIR/.repacked ] && echo "Already repacked, skipping..." && exit 0

cd `dirname $0`

PACKDIR=$(mktemp -d -t wat.XXXXXX)
LINUX_DIST=dist/cockroach-v$VERSION.linux-amd64.tgz
OSX_DIST=dist/cockroach-v$VERSION.darwin-10.9-amd64.tgz
WINDOWS_DIST=dist/cockroach-v$VERSION.windows-6.2-amd64.zip
WINDOWS_TZ_DIST=dist/zoneinfo.zip

mkdir -p dist/ target/generated-resources/
[ -e $LINUX_DIST ] || wget -O $LINUX_DIST "https://binaries.cockroachdb.com/cockroach-v$VERSION.linux-amd64.tgz"
[ -e $OSX_DIST ] || wget -O $OSX_DIST "https://binaries.cockroachdb.com/cockroach-v$VERSION.darwin-10.9-amd64.tgz"
[ -e $WINDOWS_DIST ] || wget -O $WINDOWS_DIST "https://binaries.cockroachdb.com/cockroach-v$VERSION.windows-6.2-amd64.zip"
[ -e $WINDOWS_TZ_DIST ] || wget -O $WINDOWS_TZ_DIST https://github.com/golang/go/raw/master/lib/time/zoneinfo.zip

tar xzf $LINUX_DIST -C $PACKDIR
pushd $PACKDIR/$(basename $LINUX_DIST .tgz)
tar cJf $RSRC_DIR/cockroach-Linux-x86_64.txz \
  cockroach
popd

rm -fr $PACKDIR && mkdir -p $PACKDIR

tar xzf $OSX_DIST -C $PACKDIR
pushd $PACKDIR/$(basename $OSX_DIST .tgz)
tar cJf $RSRC_DIR/cockroach-Darwin-x86_64.txz \
	cockroach
popd

rm -fr $PACKDIR && mkdir -p $PACKDIR

unzip -q -d $PACKDIR $WINDOWS_DIST
mv $WINDOWS_TZ_DIST $PACKDIR/$(basename $WINDOWS_DIST .zip)/$(basename $WINDOWS_TZ_DIST)
pushd $PACKDIR/$(basename $WINDOWS_DIST .zip)
tar cJf $RSRC_DIR/cockroach-Windows-x86_64.txz \
  cockroach.exe \
  $(basename $WINDOWS_TZ_DIST)
popd

rm -rf $PACKDIR
touch $RSRC_DIR/.repacked
