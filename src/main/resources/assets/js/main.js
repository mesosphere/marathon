require.config({
  jsx: {
    extension: "jsx"
  },
  paths: {
    "Backbone": "libs/backbone-min",
    "jsx": "libs/jsx-0.0.2",
    "JSXTransformer": "libs/JSXTransformer-0.10.0.max",
    "jquery": "libs/jquery-2.0.3",
    "mousetrap": "libs/mousetrap-1.4.6",
    "React": "libs/react-with-addons-0.10.0",
    "Underscore": "libs/underscore-min"
  },
  shim: {
    Backbone: {
      deps: ["Underscore", "jquery"],
      exports: "Backbone"
    },
    Underscore: {
      exports: "_"
    }
  }
});

require([
  "React",
  "jsx!components/Marathon"
], function(React, Marathon) {
  React.renderComponent(
    Marathon(),
    document.getElementById("marathon")
  );
});
