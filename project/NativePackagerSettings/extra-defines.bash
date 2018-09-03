if [ ! "$1" = "--help" ]; then
  # Run the specified start-hook script [optional]
  if [ -x "$HOOK_MARATHON_START" ]; then
    "$HOOK_MARATHON_START"
    # if a $HOOK_MARATHON_START.env file was produced it is evaluated
    starthook_env=${HOOK_MARATHON_START%.*}.env
    starthook_env=${starthook_env##*/}

    # In case the hook script needs to add/change environment variables or otherwise access the parent shell
    if [ -f "$starthook_env" ]; then
      set -a # export the variables so they can be seen when launching Marathon
      source "$starthook_env"
      set +a
    fi
  else
    echo "No start hook file found (\$HOOK_MARATHON_START). Proceeding with the start script."
  fi
fi
