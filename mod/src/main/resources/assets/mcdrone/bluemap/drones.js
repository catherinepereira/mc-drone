// The mod's part of BlueMap's web app, see BlueMapMarkers.
// BlueMap pushes marker sets every 10 seconds and players every second. The mod writes the drones' positions to
// drones.json every second, so this moves the drone markers as often as players move, and glides the camera after
// the drone the dashboard follows
(() => {
  const REFRESH_MS = 1000;
  // share of the way to the newest position a marker or the camera covers each frame
  const GLIDE = 0.12;
  // drone uuid to its newest [x, y, z], and the point the camera follows, null when it doesn't
  let drones = new Map();
  let follow = null;

  setInterval(() => {
    fetch(`mcdrone/drones.json?t=${Date.now()}`, { cache: "no-store" })
      .then((r) => (r.ok ? r.json() : null))
      .then((positions) => {
        if (positions) {
          drones = new Map(Object.entries(positions));
        }
      })
      .catch(() => {});
  }, REFRESH_MS);

  window.addEventListener("message", (e) => {
    if (e.origin === window.location.origin && e.data?.type === "mcdrone-follow") {
      follow = e.data.pos;
    }
  });

  const toward = (position, target) => {
    position.x += (target[0] - position.x) * GLIDE;
    position.y += (target[1] - position.y) * GLIDE;
    position.z += (target[2] - position.z) * GLIDE;
  };

  // a push puts a marker back where it was up to 10 seconds ago, so every frame moves it on toward the newest position
  const moveMarkers = (set) => {
    for (const [id, marker] of set.markers ?? []) {
      const pos = id.startsWith("drone-") ? drones.get(id.slice(6)) : undefined;
      if (pos && marker.position) {
        toward(marker.position, pos);
      }
    }
    for (const child of set.markerSets?.values() ?? []) {
      moveMarkers(child);
    }
  };

  const frame = () => {
    const viewer = window.bluemap?.mapViewer;
    if (viewer?.markers) {
      moveMarkers(viewer.markers);
    }
    // only the point the camera looks at moves, the viewer keeps their zoom and angle
    if (follow && viewer?.controlsManager?.position) {
      toward(viewer.controlsManager.position, follow);
    }
    requestAnimationFrame(frame);
  };
  requestAnimationFrame(frame);
})();
