#!/usr/bin/env bash

set -e

. "$(dirname "${BASH_SOURCE[0]}")/config.sh"

pushd "$ROOT"

# at this point we should have latest upstream changes in devtools branch
# (run ./fetch-devtools-branch.sh to update it)

# A note about initial setup
#
# git subtree needs some starting point, you use git subtree add:
#
#   git subtree add --prefix=resources/unpacked/devtools --squash devtools
#
# consequent merges are done via
#
#   git subtree merge --prefix=resources/unpacked/devtools --squash devtools
#
# read more docs here: https://github.com/apenwarr/git-subtree/blob/master/git-subtree.txt

git subtree merge --prefix="$DEVTOOLS_DIRAC_PREFIX" --squash "$DEVTOOLS_BRANCH"

popd