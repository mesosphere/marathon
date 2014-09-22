define([
  "React"
], function(React) {
  "use strict";

  function formatKey(key) {
    return key.split("_").map(function(piece) {
      return piece.charAt(0).toUpperCase() + piece.slice(1);
    }).join(" ");
  }

  function prettyPrint(object) {
    if (typeof object === "object" && !!object) {
      /* jshint trailing:false, quotmark:false, newcap:false */
      return <code>{JSON.stringify(object, null, " ")}</code>;
    } else if (typeof object === "boolean") {
      /* jshint trailing:false, quotmark:false, newcap:false */
      return <code>{object.toString()}</code>;
    } else {
      return object.toString();
    }
  }

  return React.createClass({
    displayName: "ObjectDlComponent",
    propTypes: {
      // Can be `null` or `undefined` since the data is likely fetched
      // asynchronously.
      object: React.PropTypes.object
    },

    render: function() {
      /* jshint trailing:false, quotmark:false, newcap:false */
      var dlNodes;
      if (this.props.object != null) {
        dlNodes = [];
        Object.keys(this.props.object).sort().forEach(function(key) {
          dlNodes.push(
            <dt key={key} title={key}>{formatKey(key)}</dt>
          );
          dlNodes.push(
            <dd key={key + "_val"}>
              {this.props.object[key] == null ?
                <span className="text-muted">Unspecified</span> :
                prettyPrint(this.props.object[key])}
            </dd>
          );
        }, this);
      }

      return (
        <dl className="dl-horizontal dl-horizontal-lg">
          {dlNodes}
        </dl>
      );
    }
  });
});
