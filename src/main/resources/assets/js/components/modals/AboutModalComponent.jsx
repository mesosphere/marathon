/** @jsx React.DOM */

define([
  "React",
  "models/Info",
  "mixins/BackboneMixin",
  "jsx!components/ModalComponent",
  "jsx!components/ObjectDlComponent"
], function(React, Info, BackboneMixin, ModalComponent, ObjectDlComponent) {
  return React.createClass({
    mixins: [BackboneMixin],

    getInitialState: function() {
      return {
        info: new Info()
      };
    },

    componentDidMount: function() {
      console.log("fetching")
      this.state.info.fetch();
    },

    destroy: function() {
      // This will also call `this.props.onDestroy` since it is passed as the
      // callback for the modal's `onDestroy` prop.
      this.refs.modalComponent.destroy();
    },

    getResource: function() {
      return this.state.info;
    },

    render: function() {
      var marathonConfig = this.state.info.get("marathon_config");
      var zookeeperConfig = this.state.info.get("zookeeper_config");

      /* jshint trailing:false, quotmark:false, newcap:false */
      return (
        <ModalComponent ref="modalComponent" onDestroy={this.props.onDestroy} size="lg">
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
              <dd>{this.state.info.get("framework_id") || <span className="text-muted">Undefined</span>}</dd>
              <dt title="leader">Leader</dt>
              <dd>{this.state.info.get("leader") || <span className="text-muted">Undefined</span>}</dd>
              <dt title="name">Name</dt>
              <dd>{this.state.info.get("name") || <span className="text-muted">Undefined</span>}</dd>
            </dl>
            <h4 title="marathon_config">Marathon Config</h4>
            <ObjectDlComponent object={marathonConfig} />
            <h4 title="zookeeper_config">ZooKeeper Config</h4>
            <ObjectDlComponent object={zookeeperConfig} />
          </div>
        </ModalComponent>
      );
    }
  });
});
