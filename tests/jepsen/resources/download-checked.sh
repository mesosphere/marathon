#/bin/bash
#title           :download-checked.sh
#author		     :Tim Harper
#url             :https://gist.github.com/timcharper/f2379d371960abc96d094ae395a7fe73
URL="$1"
DEST="$2"
SHA256SUM=$3

curl -L -f -o "${2}.tmp" "$1"

do_sha256sum() {
  cmd=$(which sha256sum shasum | head -n 1)
  case $(basename "$cmd") in
    sha256sum)
      sha256sum "$1" | cut -f 1 -d ' '
      ;;
    shasum)
      shasum -a 256 "$1" | cut -f 1 -d ' '
      ;;
    *)
      echo "Couldn't find shasum" 1>&2
      ;;
  esac
}

FILE_SHA256SUM="$(do_sha256sum "$2.tmp")"
if [ "$FILE_SHA256SUM" == "$SHA256SUM" ]; then
  mv $2.tmp $2
  exit 0
else
  echo "sha256sum did not match" 1>&2
  echo "Expected: $SHA256SUM" 1>&2
  echo "Got: $FILE_SHA256SUM" 1>&2
  exit 1
fi
