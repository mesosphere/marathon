def escapeString(s: String) = s""""${s}""""

def stripAnsi(msg: String) =
  msg.replaceAll("\u001B\\[[;\\d]*m", "")
