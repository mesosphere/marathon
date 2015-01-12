require.config({
  jsx: {
    extension: "jsx"
  },
  paths: {
    "domReady": "libs/domReady",
    "Backbone": "libs/backbone-min",
    "jsx": "libs/jsx-0.0.2",
    "JSXTransformer": "libs/JSXTransformer-0.12.2.max",
    "jquery": "libs/jquery-2.0.3",
    "mousetrap": "libs/mousetrap-1.4.6",
    "React": "libs/react-with-addons-0.12.2.max",
    "Router": "models/Router",
    "Underscore": "libs/underscore-min",
    "underscore.string": "libs/underscore.string.min"
  },
  shim: {
    Backbone: {
      deps: ["Underscore", "jquery"],
      exports: "Backbone"
    },
    Underscore: {
      exports: "_"
    },
    "underscore.string": {
      deps: ["Underscore"]
    }
  }
});

require([
  "Backbone",
  "React",
  "Router",
  "jsx!components/Marathon"
], function(Backbone, React, Router, Marathon) {

  React.render(
    Marathon({router: new Router()}),
    document.getElementById("marathon")
  );

  Backbone.history.start();
});
