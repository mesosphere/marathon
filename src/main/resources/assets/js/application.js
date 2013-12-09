/** @jsx React.DOM */

var App = React.createClass({
  handleClick: function(e) {
    React.renderComponent(
      <AppLightbox app={this.props.app} />,
      document.getElementById("lightbox")
    );
  },
  render: function() {
    return (
      <tr onClick={this.handleClick}>
        <td>{this.props.app.id}</td>
        <td>{this.props.app.cmd}</td>
        <td>{this.props.app.mem}</td>
        <td>{this.props.app.cpus}</td>
        <td>{this.props.app.instances}</td>
      </tr>
    );
  }
});

var AppList = React.createClass({
  componentDidMount: function() {
    this.loadJobsFromServer();
  },
  getInitialState: function() {
    return {apps: []};
  },
  loadJobsFromServer: function() {
    var _this = this;

    $.getJSON(this.props.url, function(data) {
      _this.setState({apps: data});
    });
  },
  render: function() {
    var appNodes = this.state.apps.map(function(app) {
      return <App app={app} />;
    });

    return (
      <table className="table table-hover item-table">
        <thead>
          <tr>
            <th>ID</th>
            <th>CMD</th>
            <th>Memory</th>
            <th>CPUs</th>
            <th>Instances</th>
          </tr>
        </thead>
        <tbody>
          {appNodes}
        </tbody>
      </table>
    );
  }
});

var Lightbox = React.createClass({
  remove: function() {
    var domNode = this.getDOMNode();
    React.unmountComponentAtNode(domNode);

    $(domNode).remove();
  },
  render: function() {
    return (
      <div className="lightbox" onClick={this.remove}>
        <div className="lightbox-inner">
          <div className="lb-content">
            <div className="window">
              {this.props.children}
            </div>
          </div>
        </div>
      </div>
    );
  }
})

var AppLightbox = React.createClass({
  destroyApp: function() {

  },
  render: function() {
    return (
      <Lightbox>
        <div className='app-item'>
          <div className='info-wrapper'>
            <div className="row">
              <div className="col-md-3">
                <h3 className='app-item-header'>{this.props.app.id}</h3>
              </div>
              <div className="col-md-9 text-right">
                <button className="btn btn-xs btn-default scale" onClick={this.scaleApp}>
                  SCALE
                </button>
                <button className="btn btn-xs btn-default suspend" onClick={this.suspendApp}>
                  SUSPEND
                </button>
                <button className="btn btn-xs btn-danger destroy" onClick={this.destroyApp}>
                  DESTROY
                </button>
              </div>
            </div>
            <dl className='dl-horizontal'>
              <dt>CMD:</dt><dd>{this.props.app.cmd}</dd>
              <dt className='uri-wrapper'>URIs:</dt>
              <dt>Memory (MB):</dt><dd>{this.props.app.mem}</dd>
              <dt>CPUs:</dt><dd>{this.props.app.cpus}</dd>
              <dt>Instances:</dt><dd>{this.props.app.instances}</dd>
            </dl>
          </div>
          <table className="table task-table">
            <thead>
              <tr>
                <th>ID</th>
                <th>Host</th>
                <th>Ports</th>
              </tr>
            </thead>
            <tbody>
            </tbody>
          </table>
        </div>
      </Lightbox>
    );
  },
  scaleApp: function() {

  },
  suspendApp: function() {

  }
});

React.renderComponent(
  <AppList url="/v1/apps" />,
  document.getElementById("job-list")
);
