// the mod's bridge, mirrored in Config.port and BridgeServer.ALLOWED_ORIGINS
export const DEV_BRIDGE_PORT = 8318;
export const DEV_FRONTEND_PORT = 5318;

// must match ClientRuntime.SCHEMA in the mod
export const SCHEMA = 2;

// BlueMap's web server, its default port, set in the game's config/bluemap/webserver.conf
export const DEV_BLUEMAP_PORT = 8100;

// the dev server proxies BlueMap here, so the map is same-origin and needs no CORS header
export const BLUEMAP_PATH = "/map/";
