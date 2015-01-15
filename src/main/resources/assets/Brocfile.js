// dependencies
var chalk = require("chalk");
var cleanCSS = require("broccoli-clean-css");
var concatCSS = require("broccoli-concat");
var env = require("broccoli-env").getEnv();
var filterReact = require("broccoli-react");
var jscs = require("broccoli-jscs");
var jsHintTree = require("broccoli-jshint");
var less = require("broccoli-less");
var mergeTrees = require("broccoli-merge-trees");
var pickFiles = require("broccoli-static-compiler");
var replace = require("broccoli-replace");
var uglifyJavaScript = require("broccoli-uglify-js");
var webpackify = require("broccoli-webpack");
var _ = require("underscore");

/*
 * configuration
 */
var dirs = {
  src: "",
  js: "js",
  jsDist: ".", // use . for root
  styles: "css",
  stylesDist: ".", // use . for root
  img: "img",
  imgDist: "img"
};

// without extensions
var fileNames = {
  mainJs: "main",
  mainJsDist: "main",
  mainStyles: "main",
  mainStylesDist: "main"
};

/*
 * Task definitions
 */
var tasks = {

  jsHint: function (jsTree) {
    // run jscs on compiled js
    var jscsTree = jscs(jsTree, {
      disableTestGenerator: true,
      enabled: true,
      logError: function (message) {
        // use pretty colors
        // console.log(chalk.red(message) + "\n");
      },
      jshintrcPath: dirs.js + "/.jscsrc"
    });

    // run jshint on compiled js
    var hintTree = jsHintTree(jsTree , {
      logError: function (message) {
        // use pretty colors in test and development mode
        // this._errors.push(chalk.red(message) + "\n");
      },
      jshintrcPath: dirs.js + "/.jshintrc"
    });

    // hack to strip test files from jshint tree
    hintTree = pickFiles(hintTree, {
      srcDir: "/",
      files: []
    });

    return mergeTrees(
      [jscsTree, hintTree, jsTree],
      { overwrite: true }
    );
  },

  webpack: function (masterTree) {
    // transform merge module dependencies into one file
    return webpackify(masterTree, {
      entry: "./" + fileNames.mainJs + ".js",
      output: {
        filename: dirs.jsDist + "/" + fileNames.mainJsDist + ".js"
      }
    });
  },

  minifyJs: function (masterTree) {
    return uglifyJavaScript(masterTree, {
      mangle: true,
      compress: true
    });
  },

  css: function (masterTree) {
    // create tree for less
    var cssTree = pickFiles(dirs.styles, {
      srcDir: "./",
      files: ["**/main.less", "**/*.css"],
      destDir: dirs.stylesDist
    });

    // compile less to css
    cssTree = less(cssTree, {});

    // concatenate css
    cssTree = concatCSS(cssTree, {
      inputFiles: [
        "**/*.css",
        "!" + dirs.stylesDist + "/" + fileNames.mainStyles + ".css",
        dirs.stylesDist + "/" + fileNames.mainStyles + ".css"
      ],
      outputFile: "/" + dirs.stylesDist + "/" + fileNames.mainStylesDist + ".css",
    });

    return mergeTrees(
      [masterTree, cssTree],
      { overwrite: true }
    );
  },

  minifyCSS: function (masterTree) {
    return cleanCSS(masterTree);
  },

  img: function (masterTree) {
    // create tree for image files
    var imgTree = pickFiles(dirs.img, {
      srcDir: "./",
      destDir: dirs.imgDist,
    });

    return mergeTrees(
      [masterTree, imgTree],
      { overwrite: true }
    );
  }
};

/*
 * basic pre-processing before actual build
 */
function createJsTree() {
  // create tree for .js and .jsx
  var jsTree = pickFiles(dirs.js, {
    srcDir: "./",
    destDir: "",
    files: [
      "**/*.jsx",
      "**/*.js"
    ]
  });

  // compile react files
  jsTree = filterReact(jsTree);

  // replace @@ENV in js code with current BROCCOLI_ENV environment variable
  // {default: "development" | "production"}
  return replace(jsTree, {
    files: ["**/*.js"],
    patterns: [
      {
        match: "ENV", // replaces @@ENV
        replacement: env
      }
    ]
  });
}

/*
 * Start the build
 */
var buildTree = _.compose(tasks.jsHint, createJsTree);

// export BROCCOLI_ENV={default : "development" | "production"}
if (env === "development" || env === "production" ) {
  // add steps used in both development and production
  buildTree = _.compose(
    tasks.img,
    tasks.css,
    tasks.webpack,
    buildTree
  );
}

if (env === "production") {
  // add steps that are exclusively used in production
  buildTree = _.compose(
    tasks.minifyCSS,
    tasks.minifyJs,
    buildTree
  );
}

module.exports = buildTree();
