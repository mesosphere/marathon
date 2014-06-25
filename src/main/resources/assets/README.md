#### Working on assets

When editing assets like CSS and JavaScript locally, they are loaded from the
packaged JAR by default and are not editable. To load them from a directory for
easy editing, set the `assets_path` flag when running Marathon:

    ./bin/start --master local --zk zk://localhost:2181/marathon --assets_path src/main/resources/assets/

#### Compiling Assets

*Note: You only need to follow these steps if you plan to edit the JavaScript source.*

1. Install [NPM](https://npmjs.org/)
2. Change to the assets directory

        cd src/main/resources/assets
3. Install dev dependencies

        npm install
4. Build the assets

        ./bin/build
5. The main JS file will be written to `src/main/resources/assets/js/dist/main.js`.
   Check it in.

        git add js/dist/main.js
