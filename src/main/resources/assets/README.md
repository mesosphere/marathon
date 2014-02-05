### Building JS for distribution

Compiling JS for Marathon requires [Node JS][node]. Compiling produces a single
file, `dist/main.js`.

1. Install dependencies

        npm install

2. Build the JavaScript

        ./bin/build

3. Check in the compiled JS

        git add dist/main.js

[node]: http://nodejs.org/download/
