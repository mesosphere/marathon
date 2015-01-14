require.config({
  jsx: {
    extension: "jsx"
  },
  paths: {
    "Backbone": "libs/vendor/backbone/backbone-min",
    "jsx": "libs/jsx-0.0.2",
    "JSXTransformer": "libs/vendor/react/JSXTransformer",
    "jquery": "libs/vendor/jquery/jquery",
    "mousetrap": "libs/vendor/mousetrap/mousetrap",
    "React": "libs/vendor/react/react-with-addons",
    "Underscore": "libs/vendor/underscore/underscore"
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
