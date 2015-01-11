/** @jsx React.DOM */

"use strict";

var React = require("react/addons");

var ModalComponent = require("../../components/ModalComponent");
var ObjectDlComponent = require("../../components/ObjectDlComponent");

var BackboneMixin = require("../../mixins/BackboneMixin");

var Info = require("../../models/Info");

  var UNSPECIFIED_NODE = React.createClass({
    render: function () {
      /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
      /* jshint trailing:false, quotmark:false, newcap:false */
      return (
        <span className="text-muted">Unspecified</span>
      );
    }
  });

  var AboutModalComponent = React.createClass({
    mixins: [BackboneMixin],

    getInitialState: function () {
      return {
        info: new Info()
      };
    },

    componentDidMount: function () {
      this.state.info.fetch();
    },

    destroy: function () {
      // This will also call `this.props.onDestroy` since it is passed as the
      // callback for the modal's `onDestroy` prop.
      this.refs.modalComponent.destroy();
    },

    getResource: function () {
      return this.state.info;
    },

    render: function () {
      var marathonConfig = this.state.info.get("marathon_config");
      var zookeeperConfig = this.state.info.get("zookeeper_config");

      /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
      /* jshint trailing:false, quotmark:false, newcap:false */
      return (
        <ModalComponent
            onDestroy={this.props.onDestroy}
            ref="modalComponent"
            size="lg">
          <div className="modal-header modal-header-blend">
            <button type="button" className="close"
              aria-hidden="true" onClick={this.destroy}>&times;</button>
            <h3 className="modal-title">
              <img width="160" height="27" alt="Marathon" src="/img/marathon-logo.png" />
              <small className="text-muted" style={{"margin-left": "1em"}}>
                Version {this.state.info.get("version")}
              </small>
            </h3>
          </div>
          <div className="modal-body">
            <dl className="dl-horizontal dl-horizontal-lg">
              <dt title="framework_id">Framework Id</dt>
              <dd>
                {this.state.info.get("framework_id") || <UNSPECIFIED_NODE />}
              </dd>
              <dt title="leader">Leader</dt>
              <dd>
                {this.state.info.get("leader") || <UNSPECIFIED_NODE />}
              </dd>
              <dt title="name">Name</dt>
              <dd>
                {this.state.info.get("name") || <UNSPECIFIED_NODE />}
              </dd>
            </dl>
            <h5 title="marathon_config">Marathon Config</h5>
            <ObjectDlComponent object={marathonConfig} />
            <h5 title="zookeeper_config">ZooKeeper Config</h5>
            <ObjectDlComponent object={zookeeperConfig} />
          </div>
        </ModalComponent>
      );
    }
  });

module.exports = AboutModalComponent;
