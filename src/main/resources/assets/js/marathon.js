(function(){

  // Mustache style templats {{ }}
  _.templateSettings = {
    interpolate : /\{\{(.+?)\}\}/g
  };

  var $terminal = $('#terminal'),
      $setter = $('#setter'),
      $systemText = $('#system-text'),
      $promptText = $('#prompt-text'),
      caret = $('#caret');

  $terminal.click(function(e){
    $setter.focus();
  });

  $setter.keydown(function(e){
    key(this.value);
  });

  $setter.keyup(function(e){
    key(this.value);
  });

  $setter.keypress(function(e){
    key(this.value);
  });

  function key(text) {
    write(text);
    move(text.length);
  }

  function write(text) {
    $systemText.html(text);
  }

  function move(length) {

  }

  $setter.focus();

  var data = [
    {
        "cmd": "cd sinatra_test && /usr/bin/ruby hi.rb",
        "cpus": 0.1,
        "env": {},
        "id": "sinatra",
        "instances": 5,
        "mem": 10.0,
        "port": 13195,
        "uris": [
            "http://localhost:8888/sinatra_test.tgz"
        ]
    },
    {
        "cmd": "cd rails_test && bundle exec rails server --port $PORT",
        "cpus": 1.0,
        "env": {
            "RAILS_ENV": "production"
        },
        "id": "Monorail",
        "instances": 20,
        "mem": 400.0,
        "port": 13196,
        "uris": [
            "http://datacentercomputer.s3.amazonaws.com/rails_test_app_1.9.tgz"
        ]
    },
    {
        "cmd": "cd rails_test && bundle exec rake resque:work",
        "cpus": 1.0,
        "env": {
            "RAILS_ENV": "production",
            "QUEUES": "*",
            "VERBOSE": "1"
        },
        "id": "Resque",
        "instances": 8,
        "mem": 400.0,
        "port": 14497,
        "uris": [
            "http://datacentercomputer.s3.amazonaws.com/rails_test_app_1.9.tgz"
        ]
    }
  ];

  var Item = Backbone.Model.extend();


  var Items = Backbone.Collection.extend({
    model: Item,
  });

  window.ItemView = Backbone.View.extend({
    tagName: 'li',

    template: _.template(
        "{{ id }}"
    ),

    events: {
      'click': 'select'
    },

    initialize: function() {

    },

    render: function() {
      var data = this.data()
          html = this.template(data);
      this.$el.append(html);
      return this.$el;
    },

    data: function() {
      return this.model.toJSON();
    },

    select: function() {
      console.log(this.model.get('id'));
      this.model.set('selected', true);
    }
  });

  var ItemsView = Backbone.View.extend({
    tagName: 'ul',

    events: {
      'click .add': 'addNew',
      'change:selected': 'details'
    },

    initialize: function() {
      this.collection.on('reset', this.renderAll, this);
      this.collection.on('add', this.append, this);
    },

    render: function() {
      this.$add = $("<li class='add'>+</li>");
      this.$el.append(this.$add)
      return this.$el;
    },

    renderAll: function() {
      var html = '',
          view;

      this.collection.each(function(a){
        view = new ItemView({model: a});
        this.$el.prepend(view.render());
      }, this);
    },

    addNew: function() {
      // var model = new Item();
      // this.collection.add(model);
      $('.details').addClass('open')
      this.$el.slideUp(400, function(){
        $promptText.html('New');
        $promptText.addClass('open');
        caret.hide();
      });
    },

    append: function(model) {
      model.set('id', model.cid)
      var view = new ItemView({model:model});
      this.$add.before(view.render());
    },

    details: function(model) {
      $('.details').addClass('open')
      this.$el.slideUp(200, function(){
        $promptText.html('New');
        $promptText.show();
        caret.hide();
      });
    }
  });

  window.HomeView = Backbone.View.extend({
    tagName: 'ul',
    className: 'start-view-list',

    template: _.template(
      "<li>{{ cmd }}</li>"
    ),

    initialize: function(){
      console.log('========',this.collection);
      this.collection.on('add', this.add, this);
      this.collection.on('reset', this.render, this);
    },

    render: function(){
      this.collection.each(function(model){
        console.log(this.el, model)
        this.$el.append(this.template(model.toJSON()));
      }, this);
      return this;
    },

    renderOne: function() {
      console.log(arguments);
    }
  });


  var Router = Backbone.Router.extend({
    routes: {
      'home': 'home'
    },

    initialize: function() {
      window.apps = new Items;
      window.start = new window.HomeView({
        collection: apps
      });

      $('.content').append(start.render().el);
      // window.appsView = new ItemsView({
      //   collection: apps
      // });

      // $('.list').html(window.appsView.render());
      apps.reset(data);
    },

    home: function() {
      console.log('home');
    }

  });

  window.router = new Router();
  Backbone.history.start({
    pushState: true
  });

})();