#!/bin/bash

cd "$(dirname "$0")"/..

BUILD_DIR=js/build
DIST_DIR=js/dist
SRC_DIR=js

shopt -s expand_aliases

# Find locally installed NPM binaries
alias npmexec='PATH=$(npm bin):$PATH'

# Remove previous build, start fresh
rm -rf $DIST_DIR

# Copy all non-JSX files to build directory, they need no compilation
rsync -a --exclude=*.jsx $SRC_DIR/** $BUILD_DIR

# Compile all JSX files to the build directory
npmexec jsx -x jsx $SRC_DIR $BUILD_DIR

# Remove 'jsx!' string required to use the require-jsx plugin in the browser
find $BUILD_DIR -name '*.js' | xargs sed -i.bak 's/jsx!//g'
find $BUILD_DIR -name '*.bak' | xargs rm

# Do the actual building
npmexec r.js -o $SRC_DIR/main.build.js

# Remove intermediate build artifacts
rm -rf $BUILD_DIR

# Remove all compiled files but the desired `main.js`
find $DIST_DIR -not -name 'main.js' -not -name 'dist' | xargs rm -rf
