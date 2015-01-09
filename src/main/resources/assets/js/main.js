require.config({
  jsx: {
    extension: "jsx"
  },
  paths: {
    "domReady": "libs/domReady",
    "Backbone": "libs/backbone-min",
    "jsx": "libs/jsx-0.0.2",
    "JSXTransformer": "libs/JSXTransformer-0.11.1.max",
    "jquery": "libs/jquery-2.0.3",
    "mousetrap": "libs/mousetrap-1.4.6",
    "React": "libs/react-with-addons-0.12.2",
    "ReactRouter": "libs/react-router.min",
    "react-router-shim": "libs/react-router-shim",
    "Underscore": "libs/underscore-min"
  },
  shim: {
    Backbone: {
      deps: ["Underscore", "jquery"],
      exports: "Backbone"
    },
    Underscore: {
      exports: "_"
    },
    ReactRouter: {
      deps: ["react-router-shim"],
      exports: "ReactRouter"
    }
  }
});

require([
  "jsx!components/Marathon"
], function(Marathon) {

  Marathon();
});
