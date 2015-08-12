(function() {
  /*global THREE */
  /*global TWEEN */
  /*global Marathon */
  var scene = new THREE.Scene(),
    renderer = new THREE.WebGLRenderer(),
    geometry = new THREE.Geometry(),
    camera,
    cameraControls,
    flyCamera = false,
    cameraIsMoving = false,
    viewportHeight = window.innerHeight,
    viewportWidth = window.innerWidth,
    jed = toJED(new Date()),
    maxParticles = 50000,
    particleCount = 0,
    particlesPointer = 0,
    initialParticles = [],
    added_objects = [],
    particleSystem,
    particleAttributes,
    particleUniforms,
    taskIdLookupTable = {},
    particleTexture = THREE.ImageUtils.loadTexture("./img/particle.png"),
    stagingColor = new THREE.Color(0xcccccc),
    colorScheme = {
      "uranus": new THREE.Color(0x48B978), // green
      "heliotrope": new THREE.Color(0x7F32DE), // purple
      "mercury": new THREE.Color(0xE82A78), // magenta
      "neptune": new THREE.Color(0x20D5FF) // cyan
      //"venus": new THREE.Color(0xF4B826), // yellow
      //"earth": new THREE.Color(0x2F81F7) // blue
    },
    pointCloudRadiusMin = 500,
    pointCloudRadiusMax = 10000,
    animationDirections = {
      alpha: []
    },
    hudElements = {
      totalInstancesCounter: document.getElementById("total-instances"),
      individualAppCounters: [],
      individualAppToggles: [],
      individualAppLabels: [],
      toggleGrouped: document.getElementById("group-radius"),
      toggleUngrouped: document.getElementById("ungroup-radius"),
      toggleFlyCam: document.getElementById("move-camera"),
      toggleManualCam: document.getElementById("reset-camera")
    },
    easeAlpha = 0.009,
    ease = 0.1,
    appIds = [],
    individualAppCounters = [],
    maxApps = Object.keys(colorScheme).length;

  function init() {
    var container = document.getElementById("content");
    // Individual apps HUD
    for (var i = 1; i <= maxApps; i++) {
      hudElements.individualAppCounters.push(
        document.getElementById("app-" + i + "-instances")
      );
      hudElements.individualAppToggles.push(
        document.getElementById("toggle-app-" + i )
      );
      hudElements.individualAppLabels.push(
        document.getElementById("app-" + i + "-label")
      );
      individualAppCounters[i - 1] = 0;
    }
    // Renderer
    renderer.setPixelRatio(window.devicePixelRatio);
    renderer.setSize(viewportWidth, viewportHeight);
    renderer.setClearColor(0x111111, 1);
    container.appendChild(renderer.domElement);

    // Camera
    var aspectRatio = viewportWidth / viewportHeight;
    camera = new THREE.PerspectiveCamera(90, aspectRatio, 1, 0);
    camera.position.z = pointCloudRadiusMax + 2000;
    scene.add(camera);

    // Camera controls
    cameraControls = new THREE.TrackballControls(camera, container);
    cameraControls.staticMoving = true;
    cameraControls.panSpeed = 2;
    cameraControls.zoomSpeed = 3;
    cameraControls.rotateSpeed = 3;
    cameraControls.maxDistance = pointCloudRadiusMax + pointCloudRadiusMin + 2000;
    cameraControls.dynamicDampingFactor = 0.5;

    // Generate total amount of "invisible" particles
    var numApps = Object.keys(colorScheme).length;
    var radiusStep = pointCloudRadiusMax / numApps;

    for (var i = 0; i < maxParticles; i++) {
      var colorKey = Object.keys(colorScheme)[Object.keys(colorScheme).length * Math.random() << 0];
      var targetColor = colorScheme[colorKey];
      var randomAlpha = Math.random() * (0.9 - 0.7) + 0.7;

      // Ungrouped orbits
      var minR = pointCloudRadiusMin;
      var maxR = pointCloudRadiusMax;
      var radius = maxR + (Math.random() * maxR + minR) - (maxR - minR);

      // Grouped by color
      var appIndex = Object.keys(colorScheme).indexOf(colorKey);
      var minR = pointCloudRadiusMin;
      var maxR = pointCloudRadiusMax - ((numApps - appIndex) * radiusStep) + radiusStep;
      var groupedRadius = minR + maxR + Math.random() * radiusStep - radiusStep;

      initialParticles[i] = {
        id: null,
        attributes: {
          phi: Math.random() * 360,
          theta: Math.random() * 1000 - 200,
          radius: 0,
          speed: Math.random() * 5000 + 250,
          value_color: stagingColor,
          value_alpha: 0.0,
          locked: 0
        },
        running: 0,
        targetAlpha: parseFloat(randomAlpha.toFixed(2)),
        targetColor: targetColor,
        initialRadius: radius,
        groupedRadius: groupedRadius,
        transitionEnd: {
          alpha: true,
          initialRadius: true,
          groupedRadius: true
        },
        visible: false
      };
    }

    for (var i = 0; i < maxParticles; i++) {
      var roid = initialParticles[i].attributes;

      var orbit = new Orbit3D(roid, {
        color: 0xffffff,
        display_color: new THREE.Color(0x000000),
        width: 20,
        object_size: 35,
        jed: jed,
        particle_geometry: geometry // will add itself to this geometry
      }, true);

      added_objects.push(orbit);
    }

    // reset date
    jed = toJED(new Date());

    // createParticleSystem
    particleAttributes = {
      phi: {type: "f", value: []},
      theta: {type: "f", value: []},
      radius: {type: "f", value: []},
      speed: {type: "f", value: []},
      size: {type: "f", value: []},
      value_color: {type: "c", value: []},
      value_alpha: {type: "f", value: []},
      locked: {type: "f", value: []},
      is_planet: {type: "f", value: []}
    };

    particleUniforms = {
      jed: {type: "f", value: jed},
      planet_texture: { type: "t", value: particleTexture}, // todo remove
      small_roid_texture: { type: "t", value: particleTexture},
      small_roid_circled_texture: { type: "t", value: particleTexture} // todo remove
    };

    // Shader stuff
    var vertexShader = document.getElementById("vertexshader")
      .textContent
      .replace("{{PIXELS_PER_AU}}", Number(50).toFixed(1));

    var fragmentShader = document.getElementById("fragmentshader").textContent;

    var particleSystemShaderMaterial = new THREE.ShaderMaterial({
        uniforms: particleUniforms,
        attributes: particleAttributes,
        vertexShader: vertexShader,
        fragmentShader: fragmentShader
    });

    particleSystemShaderMaterial.depthTest =  false;
    particleSystemShaderMaterial.vertexColor =  true;
    particleSystemShaderMaterial.transparent =  true;
    particleSystemShaderMaterial.blending =  THREE.AdditiveBlending;

    for (var i = 0; i < added_objects.length; i++) {
      // Assign starting values
      particleAttributes.phi.value[i] = added_objects[i].eph.phi;
      particleAttributes.theta.value[i] = added_objects[i].eph.theta;
      particleAttributes.radius.value[i] = added_objects[i].eph.radius;
      particleAttributes.size.value[i] = added_objects[i].opts.object_size;
      particleAttributes.speed.value[i] = added_objects[i].eph.speed;
      particleAttributes.value_color.value[i] = added_objects[i].eph.value_color;
      particleAttributes.value_alpha.value[i] = added_objects[i].eph.value_alpha;
      particleAttributes.locked.value[i] = 0.0;
      particleAttributes.is_planet.value[i] = 0.0;
    }

    // Flags
    particleAttributes.value_color.needsUpdate = true;
    particleAttributes.value_alpha.needsUpdate = true;
    particleAttributes.locked.needsUpdate = true;
    particleAttributes.size.needsUpdate = true;

    particleSystem = new THREE.PointCloud(
      geometry,
      particleSystemShaderMaterial
    );

    // add it to the scene
    scene.add(particleSystem);

    // Group / Ungroup apps
    hudElements.toggleGrouped.addEventListener("click", function (e) {
      e.preventDefault();
      initialParticles.forEach(function(p){
        if (p.transitionEnd.initialRadius) p.transitionEnd.groupedRadius = false;
      });
      hudElements.toggleGrouped.className = "active";
      hudElements.toggleUngrouped.className = "";
    });

    hudElements.toggleUngrouped.addEventListener("click", function (e) {
      e.preventDefault();
      initialParticles.forEach(function(p){
        p.transitionEnd.initialRadius = false;
      });
      hudElements.toggleGrouped.className = "";
      hudElements.toggleUngrouped.className = "active";
    });

    // Auto-disengage flycam on mouse drag
    container.childNodes[0].addEventListener("mousedown", function (e) {
      TWEEN.removeAll();
      cameraControls.enabled = true;
      flyCamera = false;
      cameraIsMoving = false;
      hudElements.toggleFlyCam.className = "";
      hudElements.toggleManualCam.className = "active";
    });
    // Turn flycam on
    hudElements.toggleFlyCam.addEventListener("click", function (e) {
      e.preventDefault();
      flyCamera = true;
      cameraControls.enabled = false;
      hudElements.toggleFlyCam.className = "active";
      hudElements.toggleManualCam.className = "";
    });
    // Turn flycam off
    hudElements.toggleManualCam.addEventListener("click", function (e) {
      e.preventDefault();
      TWEEN.removeAll();
      cameraControls.enabled = true;
      flyCamera = false;
      cameraIsMoving = false;
      hudElements.toggleFlyCam.className = "";
      hudElements.toggleManualCam.className = "active";
    });

    window.geometry = geometry;
    window.camera = camera;
    window.cameraControls = cameraControls;


    Marathon.Events.created(function (task) {
      var taskId = task.id;
      var j = taskIdLookupTable[taskId];
      if (j === undefined) {
        j = particlesPointer++; // pick a new particle
        taskIdLookupTable[taskId] = j;
      }
      // Update app labels in HUD
      var pos = appIds.indexOf(task.appId);
      if (pos === -1) {
        if (appIds.length < maxApps) {
          pos = appIds.push(task.appId) - 1;
          if (hudElements.individualAppLabels[pos]) {
            hudElements.individualAppLabels[pos].textContent = task.appId
              .toString()
              .toUpperCase() + " TASKS";
          }
        }
      }
      if (pos > -1) {
        individualAppCounters[pos]++;
      }

      initialParticles[j].visible = true;
      initialParticles[j].running = task.running;
      initialParticles[j].transitionEnd.alpha = false;
      initialParticles[j].transitionEnd.initialRadius = false;
    });

    Marathon.Events.updated(function (task) {
      var taskId = task.id;
      var j = taskIdLookupTable[taskId];
      if (j === undefined) {
        j = particlesPointer++; // pick a new particle
        taskIdLookupTable[taskId] = j;
      }
      initialParticles[j].visible = true;
      initialParticles[j].running = task.running;
    });
    // Kaboom
    Marathon.startPolling();
    animate();
  }

  /*
  function getAstroPos(i, jed) {
    var phi = particleAttributes.phi.value[i];
    var radius = particleAttributes.radius.value[i];
    var speed = particleAttributes.speed.value[i];
    var theta = particleAttributes.theta.value[i];

    // longitude of ascending node
    var phi_rad = (phi) * Math.PI / 180.0;
    // longitude of perihelion
    //var theta_rad = (particleAttributes.theta.value[i]) * Math.PI / 180.0;

    var t = (jed % speed) / speed * 2.0 * Math.PI;
    var X = radius * Math.sin(phi_rad + t);
    var Y = radius * Math.cos(phi_rad + t);
    var Z = theta * Math.cos((theta % 2.0 * Math.PI) + t);

    return new THREE.Vector3(X, Y, Z);
  }
  */

  function moveCamera() {
    if (cameraIsMoving) return;
    cameraIsMoving = true;
    var theta = 10;
    var x = camera.position.x;
    var y = camera.position.y;
    var z = camera.position.z;

    var moveX = x * Math.cos(theta) + z * Math.sin(theta);
    var moveY = y * Math.cos(theta) + z * Math.sin(theta);
    var moveZ = z * Math.cos(theta) - z * Math.sin(theta);

    new TWEEN.Tween(camera.position)
      .to({x: moveX, y: moveY, z: moveZ}, 15000)
      .easing(TWEEN.Easing.Cubic.InOut)
      .onUpdate(function () {
        camera.updateProjectionMatrix();
      })
      .onComplete(function () {
        flyCamera = false;
        cameraIsMoving = false;
      })
      .yoyo(true)
      .repeat(Infinity)
      .start();

  }

  function animate() {
    render();

    requestAnimationFrame(animate);

    particleUniforms.jed.value = jed;
    jed += 0.12;

    // Camera lock-on is giving us nightmares. Let's pass for now.
    //var pos = getAstroPos(20, jed);

    if (flyCamera) {
      moveCamera();
      TWEEN.update();
    }
    cameraControls.update();

    // Animation loop
    for (var i = 0; i < maxParticles; i++) {
      var p = initialParticles[i];
      var color = particleAttributes.value_color.value[i];

      // Subtle glowing effect
      if (!p.transitionEnd.alpha) {
        var alpha = particleAttributes.value_alpha.value[i];
        var targetAlpha = 1.0;
        if (alpha >= p.targetAlpha) {
          animationDirections.alpha[i] = -1;
        } else if (alpha <= 0.1) {
          animationDirections.alpha[i] = 1;
        }
        var da = targetAlpha - alpha;
        var va = da * easeAlpha;
        particleAttributes.value_alpha.value[i] +=
          va * animationDirections.alpha[i];
      }

      // Animate grouped radius
      if (!p.transitionEnd.groupedRadius &&
        p.transitionEnd.initialRadius) {
        var radius = particleAttributes.radius.value[i];
        var groupedRadius = p.groupedRadius;
        var dr = groupedRadius - radius;
        var vr = dr * ease;
        particleAttributes.radius.value[i] += vr;
        if (parseInt(radius) === parseInt(groupedRadius)) {
          initialParticles[i].transitionEnd.groupedRadius = true;
        }
      }

      // Animate to initial radius
      if (!p.transitionEnd.initialRadius &&
        p.transitionEnd.groupedRadius) {
        var radius = particleAttributes.radius.value[i];
        var initialRadius = p.initialRadius;
        var dr = initialRadius - radius;
        var vr = dr * ease;
        particleAttributes.radius.value[i] += vr;
        if (parseInt(radius) === parseInt(initialRadius)) {
          initialParticles[i].transitionEnd.initialRadius = true;
        }
      }
      // Update color for running tasks
      if (parseInt(p.running) === 1) {
        particleAttributes.value_color.value[i] = p.targetColor;
      } else {
        particleAttributes.value_color.value[i] = stagingColor;
      }
    }

    // Update global counter
    var currentTotalCounter = parseInt(hudElements.totalInstancesCounter.textContent);
    if (currentTotalCounter !== particlesPointer) {
      var dct = particlesPointer - currentTotalCounter;
      var vct = dct * ease;
      hudElements.totalInstancesCounter.textContent = Math.ceil(currentTotalCounter + vct);
    }

    // Update individual app counters
    for (var i = 0; i < maxApps; i++) {
      var currentAppCounter = parseInt(hudElements.individualAppCounters[i].textContent);
      var targetAppCounter = individualAppCounters[i];
      if (currentAppCounter !== targetAppCounter) {
        var dct = targetAppCounter - currentAppCounter;
        var vct = dct * ease;
        hudElements.individualAppCounters[i].textContent = Math.ceil(currentAppCounter + vct);
      }
    }


    // Sorry, GPU
    geometry.__dirtyVertices = true;
    particleAttributes.radius.needsUpdate = true;
    particleAttributes.phi.needsUpdate = true;
    particleAttributes.theta.needsUpdate = true;
    particleAttributes.speed.needsUpdate = true;
    particleAttributes.value_alpha.needsUpdate = true;
    particleAttributes.value_color.needsUpdate = true;
  }

  function render() {
    renderer.render(scene, camera);
  }

  function toJED(d) {
    return Math.floor((d.getTime() / (1000 * 60 * 60 * 24)) - 0.5) + 2440588;
  }

  function onWindowResize() {
    viewportWidth = window.innerWidth;
    viewportHeight = window.innerHeight;
    camera.aspect = viewportWidth / viewportHeight;
    camera.updateProjectionMatrix();
    renderer.setSize(window.innerWidth, window.innerHeight);
  }

  document.addEventListener("DOMContentLoaded", init, false);
  window.addEventListener("resize", onWindowResize);

})();
