# process_markdown.sh
#
# Replaces inline invocations of httpie in the REST documentation template
# with the command output.

TEMPLATE="./REST_template.md"
TARGET="../../../REST.md"

if [ -f $TARGET ]
	then rm $TARGET
fi

IFS='' # Preserve whitespace

echo -e "<!-- AUTO-GENERATED FILE; DO NOT MODIFY -->" >> $TARGET

while read line; do
	if [[ "$line" == http* ]]; then
		sleep 5;
		echo -e "\`\`\`" >> $TARGET
		echo -e "Evaluating \"$line\"..."
		eval $line >> $TARGET
		echo -e "\n\`\`\`" >> $TARGET
	elif [[ "$line" == :http* ]]; then
		trimmedLine=${line:1}
		echo -e "Silently Evaluating \"$trimmedLine\"..."
		eval $trimmedLine >> /dev/null
	else
		echo $line >> $TARGET
	fi
done < $TEMPLATE

exit;
