### Building JS for distribution

Compiling JS for Marathon requires [Node JS][node], the
[JSX Compiler][jsx], and the [require.js optimizer][rjs]. Compiling
produces a single file, `dist/main.js`.

1. Install dependencies

        npm install jsx
        npm install r.js

2. Build the JavaScript

        ./bin/build

3. Check in the compiled JS

        git add dist/main.js

[node]: http://nodejs.org/download/
[jsx]: https://npmjs.org/package/jsx
[rjs]: https://npmjs.org/package/rjs

