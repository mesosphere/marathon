if [ ! "$1" = "--help" ]; then
  # Run the specified start-hook script [optional]
  if [ -x "$HOOK_MARATHON_START" ]; then
    "$HOOK_MARATHON_START"
    # if a $HOOK_MARATHON_START.env file was produced it is evaluated
    starthook_env=${HOOK_MARATHON_START%.*}.env
    starthook_env=${starthook_env##*/}

    # In case the hook script needs to add/change environment variables or otherwise access the parent shell
    [ -f "$starthook_env" ] && source "$starthook_env"
  else
    echo "No start hook file found (\$HOOK_MARATHON_START). Proceeding with the start script."
  fi

  for env_op in `env | grep -v ^MARATHON_APP | grep ^MARATHON_ | awk '{gsub(/MARATHON_/,""); gsub(/=/," "); printf("%s%s ", "--", tolower($1)); for(i=2;i<=NF;i++){printf("%s ", $i)}}'`; do
    addApp "$env_op"
  done
fi
